package top.everblue.oj.community.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 社区文章可选标签的唯一来源。标签按展示分组返回，保存时只接受此词表中的名称。 */
public final class PostTagCatalog {
    private static final Map<String, List<String>> OPTIONS = createOptions();
    private static final Set<String> ALLOWED_TAGS = OPTIONS.values().stream()
            .flatMap(List::stream)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private PostTagCatalog() {
    }

    public static Map<String, List<String>> options() {
        return OPTIONS;
    }

    public static List<String> normalizeAndValidate(String rawTags) {
        return normalizeAndValidate(rawTags, Set.of());
    }

    public static List<String> normalizeAndValidate(String rawTags, Set<String> extraAllowedTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String rawTag : rawTags.replaceAll("[\\[\\]\"]", "").split(",")) {
            String tag = rawTag.trim();
            if (!tag.isEmpty()) tags.add(tag);
        }

        if (tags.size() > 8) {
            throw new IllegalArgumentException("最多选择 8 个标签");
        }
        Set<String> allowed = new LinkedHashSet<>(ALLOWED_TAGS);
        if (extraAllowedTags != null) allowed.addAll(extraAllowedTags);
        if (!allowed.containsAll(tags)) {
            throw new IllegalArgumentException("包含未被系统收录的标签，请从标签库中选择");
        }
        return List.copyOf(tags);
    }

    private static Map<String, List<String>> createOptions() {
        Map<String, List<String>> options = new LinkedHashMap<>();
        options.put("算法基础", List.of("模拟", "枚举", "排序", "前缀和", "差分", "双指针", "滑动窗口", "二分查找", "离散化", "位运算", "递归", "分治", "贪心", "构造", "倍增", "随机化", "莫队"));
        options.put("搜索", List.of("DFS", "BFS", "回溯", "剪枝", "A*", "迭代加深", "状态压缩搜索"));
        options.put("数据结构", List.of("数组", "链表", "栈", "队列", "单调栈", "单调队列", "优先队列", "堆", "哈希表", "并查集", "树状数组", "线段树", "可持久化线段树", "分块", "ST 表", "Trie", "平衡树", "Treap", "Splay", "DSU on Tree"));
        options.put("动态规划", List.of("线性 DP", "背包 DP", "完全背包", "多重背包", "状态压缩 DP", "区间 DP", "树形 DP", "数位 DP", "概率 DP", "博弈 DP", "计数 DP", "DAG DP", "单调队列优化", "斜率优化", "分治优化"));
        options.put("数学", List.of("最大公约数", "扩展欧几里得", "素数筛", "质因数分解", "同余", "快速幂", "逆元", "中国剩余定理", "组合数学", "容斥原理", "矩阵快速幂", "线性递推", "概率论", "期望", "博弈论", "计算几何", "线性代数", "FFT/NTT"));
        options.put("图论", List.of("图的遍历", "拓扑排序", "最短路", "最小生成树", "连通性", "强连通分量", "割点与桥", "二分图", "网络流", "最小割", "费用流", "二分图匹配", "LCA", "树链剖分", "点分治", "欧拉路径", "差分约束", "2-SAT"));
        options.put("字符串", List.of("字符串哈希", "KMP", "Z 函数", "Manacher", "AC 自动机", "后缀数组", "后缀自动机", "回文自动机", "字符串匹配", "字符串 DP"));
        options.put("题目平台", List.of("MuseAlgo 原创", "洛谷", "Codeforces", "AtCoder", "LeetCode", "AcWing", "牛客", "LibreOJ", "UOJ", "HDU", "POJ", "CodeChef", "HackerRank", "Kattis", "USACO", "Project Euler"));
        options.put("赛事体系", List.of("CSP-J", "CSP-S", "NOIP", "省选", "NOI", "IOI", "ICPC 区域赛", "ICPC World Finals", "CCPC", "蓝桥杯", "天梯赛", "Codeforces Round", "AtCoder ABC", "AtCoder ARC", "AtCoder AGC"));
        options.put("编程语言", List.of("C++", "C", "Python", "Java", "Rust", "Go", "JavaScript / TypeScript", "Kotlin"));
        return Collections.unmodifiableMap(options);
    }
}
