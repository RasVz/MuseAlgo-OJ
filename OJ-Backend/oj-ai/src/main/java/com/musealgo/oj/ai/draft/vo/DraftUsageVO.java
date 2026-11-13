package com.musealgo.oj.ai.draft.vo;

/**
 * 单次生成的模型开销。
 *
 * @param estimated 未拿到供应商用量时按字符数估算
 */
public record DraftUsageVO(int promptTokens, int completionTokens, int latencyMs, boolean estimated) {
}
