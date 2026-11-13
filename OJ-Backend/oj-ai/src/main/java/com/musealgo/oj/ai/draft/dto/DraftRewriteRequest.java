package com.musealgo.oj.ai.draft.dto;

/**
 * 定向重写请求。
 *
 * @param targetField 只能是 content/samples/title/tags/hint
 * @param instruction 教师对本次改写的具体要求
 */
public record DraftRewriteRequest(String targetField, String instruction) {
}
