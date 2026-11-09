package com.musealgo.oj.ai.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AiReviewRequest extends AiTeachingPreferences {
    @NotNull @Positive private Long submissionId;
    @Positive private Long contestId;
    @Size(max = 11000) private String previousAnswer;
    @Size(max = 2000) private String studentQuestion;
}
