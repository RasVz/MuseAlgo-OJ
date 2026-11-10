package com.musealgo.oj.ai.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 单一教学模式契约；回答方式与展开程度由所选模式固定。 */
@Data
public class AiTeachingOptions {
    public enum HelpMode { AUTO, READ_PROBLEM, GET_HINT, CHECK_APPROACH, DEBUG_CODE, FULL_SOLUTION }
    @NotNull private HelpMode helpMode = HelpMode.AUTO;
}
