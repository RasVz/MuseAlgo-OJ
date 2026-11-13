package com.musealgo.oj.problem.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 客观题作答提交。 */
@Data
public class ExamAnswerSubmitDTO {

    @NotNull(message = "作答不能为空")
    @Size(max = 500, message = "单次最多提交 500 道题的答案")
    private List<@NotNull @Valid AnswerItem> answers = new ArrayList<>();

    @Data
    public static class AnswerItem {
        @NotNull(message = "题目 ID 不能为空")
        @Positive(message = "题目 ID 不合法")
        private Long questionId;

        @Size(max = 255, message = "答案不能超过 255 个字符")
        private String answer;
    }
}
