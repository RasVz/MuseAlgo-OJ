package com.musealgo.oj.ai.draft.vo;

import com.musealgo.oj.ai.draft.DraftIssue;

import java.util.List;

/**
 * 落库结果。
 *
 * @param created       本次是否真的新建了题目；false 表示该草稿此前已保存，返回已有题目
 * @param appliedFields 实际写入的字段（只含真正写了非空值的字段）
 * @param issues        保存时仍然存在的全部质量问题（含被 allowIssues 越过的阻断项）
 */
public record DraftSaveResultVO(Long problemId, boolean created, List<String> appliedFields,
                                List<DraftIssue> issues) {
}
