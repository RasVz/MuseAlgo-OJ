package com.musealgo.oj.problem.format;

import java.util.List;

/**
 * 创建批量整理任务。
 *
 * @param problemIds 题目 ID 列表（1~30，去重后）
 * @param ipMode     是否同时做睿思星球 IP 改名
 * @param autoApply  生成后无阻断问题是否直接落库；false 时全部进入待审
 */
public record ProblemFormatJobRequest(List<Long> problemIds, Boolean ipMode, Boolean autoApply) {

    public boolean ipEnabled() {
        return Boolean.TRUE.equals(ipMode);
    }

    /** 默认自动应用：保持「一键整理」的原有手感，但只对通过质量闸门的题目生效。 */
    public boolean autoApplyEnabled() {
        return !Boolean.FALSE.equals(autoApply);
    }
}
