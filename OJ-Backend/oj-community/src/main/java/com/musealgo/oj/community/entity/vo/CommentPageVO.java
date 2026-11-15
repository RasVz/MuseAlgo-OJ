package com.musealgo.oj.community.entity.vo;

import java.util.List;

/**
 * 顶层评论分页结果。
 * <p>
 * {@code records} 包含当前页的顶层评论及其全部已加载回复，前端仍按
 * {@code parentId} 组树；{@code total} 是顶层评论总数，用于判断是否还有下一页。
 * </p>
 */
public record CommentPageVO(List<CommentVO> records, long total) {
}
