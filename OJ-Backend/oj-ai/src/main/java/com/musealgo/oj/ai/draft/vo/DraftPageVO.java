package com.musealgo.oj.ai.draft.vo;

import java.util.List;

/** 历史分页。{@code groupId} 查询时不分页，{@code total} 即组内方案数。 */
public record DraftPageVO(long total, int page, int size, List<DraftVO> items) {
}
