package top.everblue.oj.problem.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 客观题作答提交。 */
@Data
public class ExamAnswerSubmitDTO {

    @NotNull(message = "作答不能为空")
    private List<AnswerItem> answers = new ArrayList<>();

    @Data
    public static class AnswerItem {
        @NotNull(message = "题目 ID 不能为空")
        private Long questionId;

        private String answer;
    }
}
