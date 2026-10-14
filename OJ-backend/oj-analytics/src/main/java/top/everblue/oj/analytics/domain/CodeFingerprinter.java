package top.everblue.oj.analytics.domain;

import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterCpp;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 代码指纹器：AST 级轻量实现优先，native 不可用/解析失败时回退 TOKEN 级。
 * <p>
 * <b>AST 路径（method=AST）</b>：tree-sitter C++ grammar 解析源码，前序遍历提取
 * named 节点类型序列（跳过 {@code preproc_*} 模板头子树），得到结构指纹。
 * <b>TOKEN 路径（method=TOKEN）</b>：归一化 token 序列（{@link CodeNormalizer}）。
 * <p>
 * 两条路径统一做 5-gram shingle + MinHash（64 维最小签名），
 * 相似度 = 两签名逐位一致比例（Jaccard 估计）。
 * </p>
 */
public final class CodeFingerprinter {

    /** MinHash 签名维度 */
    public static final int SIGNATURE_SIZE = 64;
    /** 滑窗 shingle 长度 */
    public static final int SHINGLE_SIZE = 5;
    /** AST 指纹方法名 */
    public static final String METHOD_AST = "AST";
    /** TOKEN 指纹方法名 */
    public static final String METHOD_TOKEN = "TOKEN";

    /** 大素数 2^31-1，哈希取模空间 */
    private static final long PRIME = 2147483647L;
    /** 随机系数 [a, b]，h_i(x) = (a * hash(x) + b) mod P */
    private static final long[][] HASH_PARAMS = buildHashParams();

    /** native 库加载失败是否已告警（只告警一次，避免刷日志） */
    private static volatile boolean nativeWarningLogged = false;

    /**
     * 指纹缓存（M2）：相同代码不重复走 AST/token 解析。
     * <p>
     * 查重批次内同一份代码会与多份代码比对（O(n²) 中指纹只算 O(n)），
     * 缓存按代码原文为 key；容量上限防内存膨胀，超限整体清空
     * （批次式突发负载下简单有效，无需 LRU 复杂度）。
     * </p>
     */
    private static final int CACHE_MAX_SIZE = 4096;
    private static final java.util.Map<String, Fingerprint> FINGERPRINT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>(256);

    private CodeFingerprinter() {
    }

    /** 指纹：64 维 MinHash 签名 + 指纹方法 + 是否空指纹（无可比内容） */
    public record Fingerprint(long[] signature, String method, boolean empty) {
    }

    /**
     * 计算代码指纹：优先 AST，失败回退 TOKEN。相同代码走缓存（M2）。
     *
     * @param rawCode 原始代码
     * @return 指纹（空/空白输入返回 empty=true 的指纹）
     */
    public static Fingerprint fingerprint(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return new Fingerprint(new long[SIGNATURE_SIZE], METHOD_TOKEN, true);
        }
        if (FINGERPRINT_CACHE.size() > CACHE_MAX_SIZE) {
            FINGERPRINT_CACHE.clear();
        }
        return FINGERPRINT_CACHE.computeIfAbsent(rawCode, CodeFingerprinter::compute);
    }

    /** 实际计算路径（缓存未命中时调用） */
    private static Fingerprint compute(String rawCode) {
        List<String> astTypes = tryExtractAstTypes(rawCode);
        if (astTypes != null) {
            return build(astTypes, METHOD_AST);
        }
        List<String> tokens = CodeNormalizer.normalize(rawCode);
        return build(tokens, METHOD_TOKEN);
    }

    /**
     * 两指纹相似度（0.0 ~ 1.0）。任一为空返回 0。
     */
    public static double similarity(Fingerprint a, Fingerprint b) {
        if (a == null || b == null || a.empty() || b.empty()) {
            return 0.0;
        }
        int equal = 0;
        long[] sa = a.signature();
        long[] sb = b.signature();
        for (int i = 0; i < SIGNATURE_SIZE; i++) {
            if (sa[i] == sb[i]) {
                equal++;
            }
        }
        return (double) equal / SIGNATURE_SIZE;
    }

    /**
     * tree-sitter 解析提取 named 节点类型序列。
     *
     * @return 类型序列；native 不可用/解析失败/无结构时返回 null（调用方回退 TOKEN）
     */
    private static List<String> tryExtractAstTypes(String code) {
        try {
            TSParser parser = new TSParser();
            if (!parser.setLanguage(new TreeSitterCpp())) {
                return null;
            }
            TSTree tree = parser.parseString(null, code);
            if (tree == null) {
                return null;
            }
            TSNode root = tree.getRootNode();
            List<String> types = new ArrayList<>();
            collectNamedTypes(root, types);
            return types.isEmpty() ? null : types;
        } catch (Throwable t) {
            // native 库缺失/链接失败/语法异常 → 回退 token 级指纹
            if (!nativeWarningLogged) {
                nativeWarningLogged = true;
                System.err.println("[oj-analytics] tree-sitter 不可用，查重引擎回退 TOKEN 级指纹: " + t);
            }
            return null;
        }
    }

    /** 前序遍历 named 节点类型；跳过 preproc_* 子树（模板头，#include/#define）与 comment 节点 */
    private static void collectNamedTypes(TSNode node, List<String> out) {
        String type = node.getType();
        if (type == null) {
            return;
        }
        if ("comment".equals(type) || type.startsWith("preproc")) {
            return;
        }
        if (node.isNamed()) {
            out.add(type);
        }
        int childCount = node.getNamedChildCount();
        for (int i = 0; i < childCount; i++) {
            collectNamedTypes(node.getNamedChild(i), out);
        }
    }

    /** 单元序列 → 5-gram shingle → 64 维最小 MinHash 签名 */
    private static Fingerprint build(List<String> units, String method) {
        List<String> shingles = toShingles(units);
        if (shingles.isEmpty()) {
            return new Fingerprint(new long[SIGNATURE_SIZE], method, true);
        }
        long[] signature = new long[SIGNATURE_SIZE];
        for (int k = 0; k < SIGNATURE_SIZE; k++) {
            long min = Long.MAX_VALUE;
            for (String shingle : shingles) {
                long h = hash(shingle, k);
                if (h < min) {
                    min = h;
                }
            }
            signature[k] = min;
        }
        return new Fingerprint(signature, method, false);
    }

    /** 滑窗 shingle；不足窗长时整体作为单个 shingle */
    private static List<String> toShingles(List<String> units) {
        if (units.isEmpty()) {
            return List.of();
        }
        if (units.size() <= SHINGLE_SIZE) {
            return List.of(String.join("\u0001", units));
        }
        List<String> out = new ArrayList<>(units.size() - SHINGLE_SIZE + 1);
        for (int i = 0; i + SHINGLE_SIZE <= units.size(); i++) {
            out.add(String.join("\u0001", units.subList(i, i + SHINGLE_SIZE)));
        }
        return out;
    }

    /** 第 k 个哈希函数作用于 shingle */
    private static long hash(String shingle, int k) {
        long h = 0;
        for (int i = 0; i < shingle.length(); i++) {
            h = (h * 31 + shingle.charAt(i)) % PRIME;
        }
        long[] p = HASH_PARAMS[k];
        return (h * p[0] + p[1]) % PRIME;
    }

    /** 固定种子生成 64 组随机系数（a 恒为奇数，保证与 P 互质） */
    private static long[][] buildHashParams() {
        Random random = new Random(0x5EEDC0DE);
        long[][] params = new long[SIGNATURE_SIZE][2];
        for (int i = 0; i < SIGNATURE_SIZE; i++) {
            params[i][0] = Math.abs(random.nextLong()) % PRIME | 1L; // 奇数
            params[i][1] = Math.abs(random.nextLong()) % PRIME;
        }
        return params;
    }
}
