package com.musealgo.oj.ai.draft;

/**
 * 一组公开样例。
 * <p>
 * {@code explanation} 只保留在草稿与复制文本里：平台 {@code problem_sample} 表只有输入与输出，
 * 落库时不会写入解释，这一点在保存确认里对教师明示，不做静默丢弃。
 * </p>
 */
public record DraftSample(String input, String output, String explanation) {

    public DraftSample {
        input = input == null ? "" : input;
        output = output == null ? "" : output;
        explanation = explanation == null ? "" : explanation;
    }

    public static DraftSample empty() {
        return new DraftSample("", "", "");
    }
}
