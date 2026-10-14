package top.everblue.oj.analytics.domain;

import top.everblue.oj.api.profile.ProfileQuery.WeekOverview;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 学生完整画像——由 {@code ProfileApplicationService} 组装。
 * <p>
 * 直接序列化为 JSON 后：
 * <ul>
 *   <li>前端 /profile/me 接口返回</li>
 *   <li>存入 {@code student_profile_snapshot.snapshot_json}</li>
 *   <li>喂给报告模板渲染 PDF</li>
 * </ul>
 */
public record StudentProfile(
        Long studentId,
        String studentName,
        Long classId,
        String className,
        LocalDate periodStart,
        LocalDate periodEnd,

        // --- 周概览 ---
        WeekOverview weekOverview,

        // --- 按 tag 的掌握度明细 ---
        List<TagMastery> tagMasteries,

        // --- 按知识域（雷达图维度）的掌握度上卷 ---
        Map<String, DimensionMastery> dimensionMasteries,

        // --- 班级百分位（可选，阶段 2 先留 null，阶段 4 填充） ---
        Map<String, Integer> dimensionPercentiles,

        // --- 薄弱环节（mastery < 55 的维度） ---
        List<TagMastery> weakPoints
) {
    /**
     * 知识域级掌握度摘要。
     */
    public record DimensionMastery(
            String dimension,
            String label,
            int attempted,
            int passed,
            int mastery,
            MasteryLevel level
    ) {}
}