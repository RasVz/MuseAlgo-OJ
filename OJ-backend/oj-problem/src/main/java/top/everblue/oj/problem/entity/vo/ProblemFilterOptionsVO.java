package top.everblue.oj.problem.entity.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 题库筛选面板的可选项集合，一次性返回标签（按维度分组）与来源列表。
 * <p>
 * 只收录<b>已被公开题目用到</b>的取值：直接列出全量标签会出现大量点下去
 * 没有结果的筛选项。
 * </p>
 */
@Data
public class ProblemFilterOptionsVO {
    private Map<String, List<String>> tagsByDimension;
    private List<String> sources;
}
