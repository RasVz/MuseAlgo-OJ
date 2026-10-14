package top.everblue.oj.problem.domain;

import top.everblue.oj.problem.entity.ExamQuestion;

import java.util.stream.Collectors;

/**
 * 客观题判分（提交时即时判题）。
 * <ul>
 *   <li>SINGLE：作答 = 单个选项键（A/B/C/D），忽略大小写与空白；</li>
 *   <li>MULTIPLE：作答 = 多个选项键（如 ABD），按集合比较，忽略顺序；</li>
 *   <li>JUDGE：作答 = T/F（兼容 true/false/对/错/√/×）。</li>
 * </ul>
 */
public final class ObjectiveJudge {

    private ObjectiveJudge() {
    }

    /**
     * 判一道客观题：作答与标准答案是否一致（全对才得分，多选少选/错选均 0 分）
     * <p>
     * <b>判分规则：全对才得分，不设部分分。</b>多选题少选、错选一律 0 分，
     * 这是与常规考试一致的口径。若将来要引入部分分（如「漏选得一半」），
     * 得分计算必须从比较逻辑改为返回分数，而不是在 {@code isCorrect} 里打补丁——
     * 返回 boolean 的签名承载不了分数语义，调用方会静默把部分分丢弃。
     * </p>
     * <p>
     * <b>fail-safe：</b>题目、标准答案或作答任一为 null / 空，都判为不正确而非抛异常。
     * 判分发生在提交链路上，一次异常会让整份试卷的学生看不到分数。
     * </p>
     *
     * @param question      题目及其标准答案
     * @param studentAnswer 学生作答原文，允许带空白与大小写
     * @return 完全一致返回 true
     */
    public static boolean isCorrect(ExamQuestion question, String studentAnswer) {
        if (question == null || question.getAnswer() == null || studentAnswer == null) {
            return false;
        }
        String correct = normalize(question.getQuestionType(), question.getAnswer());
        String student = normalize(question.getQuestionType(), studentAnswer);
        return correct != null && correct.equals(student);
    }

    /**
     * 归一化：按题型把作答整理成可比较的规范形式；非法作答返回 null
     * <p>
     * 标准答案与学生作答走<b>同一个</b>归一化函数，这是"忽略顺序与格式"能成立的前提；
     * 若只归一化学生作答，遇到用 {@code B,A,D} 之类顺序书写的标准答案就会误判为错。
     * </p>
     *
     * @param questionType 题型（{@code SINGLE} / {@code MULTIPLE} / {@code JUDGE}），大小写不敏感
     * @param raw          原始作答文本
     * @return 规范形式；输入为空或无法识别时返回 null
     */
    public static String normalize(String questionType, String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (ExamQuestion.TYPE_JUDGE.equalsIgnoreCase(questionType)) {
            return normalizeJudge(trimmed);
        }
        if (ExamQuestion.TYPE_MULTIPLE.equalsIgnoreCase(questionType)) {
            // 排序后再拼接，使 "DBA" 与 "ABD" 归一化为同一字符串 → 多选忽略作答顺序。
            // 同时过滤非字母字符，顺带容忍分隔符与多余空白。
            return trimmed.toUpperCase().chars()
                    .filter(Character::isLetter)
                    .sorted()
                    .mapToObj(c -> String.valueOf((char) c))
                    .collect(Collectors.joining());
        }
        // 单选：只取第一个字母键
        // 前端提交的正常作答本就只有一个字母；「取第一个」是为容忍脏数据
        // （如复制粘贴带上了 "A. 选项内容"），而不是打算支持真正的多选单选。
        String upper = trimmed.toUpperCase();
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                return String.valueOf(c);
            }
        }
        return null;
    }

    private static String normalizeJudge(String raw) {
        return switch (raw.toUpperCase()) {
            case "T", "TRUE", "对", "√", "正确" -> "T";
            case "F", "FALSE", "错", "×", "X", "错误" -> "F";
            default -> null;
        };
    }
}
