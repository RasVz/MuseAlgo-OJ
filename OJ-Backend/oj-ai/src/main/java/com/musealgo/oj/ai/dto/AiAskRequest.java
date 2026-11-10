package com.musealgo.oj.ai.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 开放式教学请求；题面由服务端按权限读取，旧题面/级别字段不再参与编排。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AiAskRequest extends AiTeachingOptions {
    @NotNull @Positive private Long problemId;
    @Positive private Long contestId;
    @Size(max = 16000) private String studentCode;
    @NotBlank @Size(max = 32) private String language = "cpp";
    @NotBlank @Size(max = 2000) private String studentQuestion;
    @Size(max = 5) private java.util.List<@NotBlank @Size(max=64) String> runIds = java.util.List.of();
    @jakarta.validation.Valid private LocalSample selectedSample;
    @Data public static class LocalSample {
        @NotNull @Size(max=4096) private String input;
        @Size(max=4096) private String expectedOutput;
        private boolean hasExpected;
        @Size(max=200) private String note;
    }
}
