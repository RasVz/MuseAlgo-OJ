package com.musealgo.oj.ai.draft.dto;

import java.util.List;

/**
 * 落库请求。
 *
 * @param fields      采纳字段；为空表示全选。title 与 content 是题目的必填项，服务端始终写入
 * @param timeLimit   时限（ms），缺省沿用草稿值
 * @param memoryLimit 内存（MB），缺省沿用草稿值
 * @param allowIssues 存在阻断问题时是否仍然保存；默认 false，需前端明确确认
 * @param force       该草稿已保存过时为 true 才会再建一道新题，避免重复点击产生重复题目
 */
public record DraftSaveRequest(List<String> fields, Integer timeLimit, Integer memoryLimit,
                               Boolean allowIssues, Boolean force) {

    public boolean allowIssuesOrDefault() {
        return Boolean.TRUE.equals(allowIssues);
    }

    public boolean forceOrDefault() {
        return Boolean.TRUE.equals(force);
    }
}
