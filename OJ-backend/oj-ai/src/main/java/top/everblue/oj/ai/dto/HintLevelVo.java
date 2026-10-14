package top.everblue.oj.ai.dto;

/**
 * 教师端 Hint Ladder 解锁情况行（按班级 + 题目聚合）。
 */
public record HintLevelVo(Long userId, String username, int level) {
}
