package top.everblue.oj.problem.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GESP 等第预估（E4）：按试卷总分映射 1–8 级。
 * 阈值可配（system_config 键 exam.gesp-thresholds，格式 1:40;2:50;...），默认如下。
 */
public final class GespLevelEstimator {

    private GespLevelEstimator() {
    }

    /** 默认阈值：level -> 达到该级所需最低分（满分 100） */
    public static Map<Integer, Integer> defaultThresholds() {
        Map<Integer, Integer> thresholds = new LinkedHashMap<>();
        thresholds.put(1, 40);
        thresholds.put(2, 50);
        thresholds.put(3, 60);
        thresholds.put(4, 65);
        thresholds.put(5, 70);
        thresholds.put(6, 75);
        thresholds.put(7, 85);
        thresholds.put(8, 90);
        return thresholds;
    }

    /**
     * 解析 "1:40;2:50;3:60" 格式；非法输入回退默认
     * <p>
     * <b>用 {@link LinkedHashMap} 保留配置书写顺序</b>，因为
     * {@link #estimate(int, Map)} 靠「升序覆盖」取最高达标等第。
     * 若换成无序的 Map（如 {@code HashMap}），键序变为哈希序，等第结果会随 key 分布而变——
     * 这类错误不会抛异常，只会安静地给出错误等第，因此顺序依赖必须显式保留。
     * </p>
     */
    public static Map<Integer, Integer> thresholdsFromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultThresholds();
        }
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (String pair : raw.split(";")) {
            String[] parts = pair.trim().split(":");
            if (parts.length != 2) {
                continue;
            }
            try {
                result.put(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException ignored) {
                // 跳过非法条目
            }
        }
        return result.isEmpty() ? defaultThresholds() : result;
    }

    /**
     * 总分 -> 等第（0 = 未达 1 级）
     * <p>
     * 遍历时用"最后一个达标项覆盖前一个"而非取 max，是为了让<b>非升序</b>的阈值表
     * 也给出「配置里最后命中的等第」这一确定结果（对 {@link LinkedHashMap} 而言即最高等第）。
     * <b>前置条件：</b>{@code thresholds} 必须按等级升序迭代，见
     * {@link #thresholdsFromConfig(String)} 中对 {@code LinkedHashMap} 的依赖说明。
     * </p>
     *
     * @param score      考生总分
     * @param thresholds 等级 → 最低分阈值，须按等级升序
     * @return 1–8 的等第，未达最低等第返回 0
     */
    public static int estimate(int score, Map<Integer, Integer> thresholds) {
        int level = 0;
        for (Map.Entry<Integer, Integer> entry : thresholds.entrySet()) {
            if (score >= entry.getValue()) {
                level = entry.getKey();
            }
        }
        return level;
    }
}
