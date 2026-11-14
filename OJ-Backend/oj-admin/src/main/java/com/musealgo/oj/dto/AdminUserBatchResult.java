package com.musealgo.oj.dto;

/** 批量用户操作结果：requested 为去重后的目标数，updated 为实际写入数。 */
public record AdminUserBatchResult(int requested, int updated) {
}
