package com.musealgo.oj.ai.draft.vo;

import com.musealgo.oj.ai.draft.DraftBrief;
import com.musealgo.oj.ai.draft.DraftContent;
import com.musealgo.oj.ai.draft.DraftIssue;

import java.util.List;

/**
 * 草稿详情：列表字段放在 {@code draft} 里，其余为审阅所需的完整信息。
 * <p>
 * 嵌套而不是把 20 多个字段平铺：前端「列表项」与「详情」共用同一个 {@link DraftVO}，
 * 状态更新时只需替换 {@code draft} 节点，不会出现两套字段名。
 * </p>
 */
public record DraftDetailVO(
        DraftVO draft,
        DraftBrief brief,
        DraftContent content,
        List<DraftIssue> issues,
        DraftPromptVO prompt,
        DraftUsageVO usage,
        String errorCode) {
}
