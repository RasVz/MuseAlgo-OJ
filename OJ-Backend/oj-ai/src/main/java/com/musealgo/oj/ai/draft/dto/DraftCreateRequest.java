package com.musealgo.oj.ai.draft.dto;

import com.musealgo.oj.ai.draft.DraftBrief;

/** 受理生成：{@code variants} 为方案数，1~3，缺省 1。 */
public record DraftCreateRequest(DraftBrief brief, Integer variants) {

    public int variantsOrDefault() {
        if (variants == null) {
            return 1;
        }
        return Math.max(1, Math.min(3, variants));
    }
}
