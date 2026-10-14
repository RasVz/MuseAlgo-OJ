package top.everblue.oj.problem.entity.dto;

import lombok.Data;
import java.util.List;

/**
 * 题单排序的入参（按题单类型分别指定顺序）。
 * <p>
 * 用 {@code type} + 全量 ID 列表成对下发，而不是单个 {@code listId}：
 * 题单首页要一次拿到多个题单的展示顺序，逐个请求会产生 N+1 次往返。
 * </p>
 */
@Data
public class ProblemListSortDTO {
    /**
     * 题单类型（如 2=官方题单）
     */
    private Integer type;

    /**
     * 排好序的题单 ID 列表（全量，下标即顺序 0..n-1）
     */
    private List<Long> ids;
}
