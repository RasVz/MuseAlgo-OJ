package com.musealgo.oj.ai.draft.vo;

/** 提示词预览：与真实调用使用同一份文本，便于教师判断「我填的简报如何变成要求」。 */
public record DraftPromptVO(String system, String user) {
}
