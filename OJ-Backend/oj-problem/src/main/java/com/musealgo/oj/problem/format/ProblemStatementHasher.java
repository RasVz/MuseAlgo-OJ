package com.musealgo.oj.problem.format;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 题面内容哈希：作为「预览 → 应用」之间的乐观锁基线。
 * <p>
 * 为什么不用 {@code problem.update_time}：MySQL {@code DATETIME} 默认秒级精度，
 * 同一秒内的两次修改无法区分。为什么不用新增 version 列：题目实体被全站多处
 * {@code updateById} 写入，引入 version 会把乐观锁语义扩散到无关调用方。
 * 这里只对「格式化会改的 8 个字段 + 样例」取哈希，恰好覆盖 apply 的写入面。
 * </p>
 */
public final class ProblemStatementHasher {

    private ProblemStatementHasher() {
    }

    public static String hash(ProblemStatementSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        StringBuilder canonical = new StringBuilder();
        append(canonical, snapshot.title());
        append(canonical, snapshot.content());
        append(canonical, snapshot.inputDesc());
        append(canonical, snapshot.outputDesc());
        append(canonical, snapshot.hint());
        append(canonical, snapshot.timeLimitMs());
        append(canonical, snapshot.memoryLimitMb());
        List<FormatSample> samples = snapshot.samples() == null ? List.of() : snapshot.samples();
        append(canonical, samples.size());
        for (FormatSample sample : samples) {
            append(canonical, sample == null ? "" : sample.input());
            append(canonical, sample == null ? "" : sample.output());
        }
        return sha256(canonical.toString());
    }

    /** 字段之间用 NUL 分隔，避免 "ab"+"c" 与 "a"+"bc" 撞成同一串。 */
    private static void append(StringBuilder target, Object value) {
        target.append(value == null ? "" : value).append('\u0000');
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须提供 SHA-256；缺失属于运行时环境损坏，不做静默降级
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
