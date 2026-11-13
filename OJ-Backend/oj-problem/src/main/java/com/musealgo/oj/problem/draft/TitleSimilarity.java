package com.musealgo.oj.problem.draft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 标题相似度（字符集合与 3-gram 的加权 Jaccard）。
 * <p>
 * 用途只有一个：生成草稿时提示「题库里可能已有同题」。它不是查重判决，所以不需要语义模型，
 * 也不需要向量库；标题这种短文本用 3-gram 已经能覆盖「换词序」「加前缀」「同义改写少量字」三类情况，
 * 而且完全可解释、可单测。中文按码点切分，避免代理对被截断。
 * </p>
 */
public final class TitleSimilarity {

    /** 低于这个值基本是「同一个知识点的不同题」，提示出来只会造成噪音。 */
    public static final double DEFAULT_THRESHOLD = 0.45;

    private TitleSimilarity() {
    }

    public static double score(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        // 纯 3-gram 对「词序调换」不敏感却会把同一批字切成不同片段，
        // 中文短标题尤其明显（"星海市的最短路径" vs "最短路径星海市" 只有 0.375）。
        // 因此与「字符集合 Jaccard」加权：字符集合对换序免疫，3-gram 负责区分用字相近的不同题。
        double charScore = jaccard(chars(a), chars(b));
        double gramScore = jaccard(grams(a), grams(b));
        return 0.45 * charScore + 0.55 * gramScore;
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        int union = left.size() + right.size() - intersection.size();
        return union == 0 ? 0 : (double) intersection.size() / union;
    }

    private static Set<String> chars(String text) {
        Set<String> result = new HashSet<>();
        text.codePoints().forEach(code -> result.add(new String(Character.toChars(code))));
        return result;
    }

    /** 只保留字母、数字与汉字：标点、空格、《》、「」这类差异不应影响相似度。 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        text.toLowerCase(Locale.ROOT).codePoints()
                .filter(code -> Character.isLetterOrDigit(code))
                .forEach(builder::appendCodePoint);
        return builder.toString();
    }

    /** 短标题（<3 码点）退化成 1-gram，否则集合为空、相似度恒为 0。 */
    private static Set<String> grams(String text) {
        List<Integer> points = new ArrayList<>(text.length());
        text.codePoints().forEach(points::add);
        int size = points.size() < 3 ? 1 : 3;
        Set<String> grams = new HashSet<>();
        for (int index = 0; index + size <= points.size(); index++) {
            StringBuilder gram = new StringBuilder();
            for (int offset = 0; offset < size; offset++) {
                gram.appendCodePoint(points.get(index + offset));
            }
            grams.add(gram.toString());
        }
        return grams;
    }
}
