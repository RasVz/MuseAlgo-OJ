package com.musealgo.oj.ai.draft.dto;

import com.musealgo.oj.ai.draft.DraftBrief;

/** 提示词预览：返回真实的 system 与 user 文本，不调用模型。 */
public record DraftPromptPreviewRequest(DraftBrief brief) {
}
