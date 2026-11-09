package com.musealgo.oj.ai.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AiTeachingPreferences {
    public enum HelpDirection { AUTO, READ_PROBLEM, GET_STARTED, CHECK_APPROACH, DEBUG_CODE, EXPLAIN_CODE, TEST_CASES, OPTIMIZE, FULL_SOLUTION }
    public enum AnswerStyle { DIRECT, HINT_ONLY }
    public enum DetailLevel { CONCISE, DETAILED }
    @NotNull private HelpDirection helpDirection = HelpDirection.AUTO;
    @NotNull private AnswerStyle answerStyle = AnswerStyle.DIRECT;
    @NotNull private DetailLevel detailLevel = DetailLevel.CONCISE;
}
