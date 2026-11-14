package com.musealgo.oj.ai.review;

import com.musealgo.oj.ai.dto.AiTeachingOptions;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ReviewCreateRequest extends AiTeachingOptions {
    public enum Relation { INITIAL, REANALYZE, FOLLOW_UP, RETRY }
    @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") private String requestId;
    @NotNull @Positive private Long submissionId;
    @Size(max = 2000) private String studentQuestion;
    @Positive private Long parentReviewId;
    @NotNull private Relation relationType = Relation.INITIAL;
}
