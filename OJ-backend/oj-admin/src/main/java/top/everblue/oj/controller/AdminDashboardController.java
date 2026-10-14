package top.everblue.oj.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.ai.query.AiUsageQueryService;
import io.swagger.v3.oas.annotations.Operation;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;

/**
 * 管理员运维仪表盘。
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final JdbcTemplate jdbcTemplate;
    private final AiUsageQueryService aiUsageQueryService;

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        // 用户统计
        data.put("totalUsers", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `user`", Long.class));
        data.put("totalProblems", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM problem", Long.class));
        data.put("totalSubmissions", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submission", Long.class));
        data.put("totalContests", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contest", Long.class));
        // 今日提交数
        data.put("todaySubmissions", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission WHERE DATE(create_time) = CURDATE()", Long.class));
        // 判题任务积压
        data.put("pendingJudgeTasks", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM judge_task WHERE status = 'PENDING'", Long.class));
        data.put("failedJudgeTasks", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM judge_task WHERE status = 'FAILED'", Long.class));
        // 最近提交时间
        data.put("latestSubmission", jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(create_time), NOW()) FROM submission", LocalDateTime.class));
        data.put("dbTime", LocalDateTime.now());
        return Result.success(data);
    }

    @Operation(summary = "运维趋势（近 N 天：提交/判题失败/AI 调用）")
    @GetMapping("/trends")
    public Result<Map<String, Object>> trends(@RequestParam(defaultValue = "7") int days) {
        LocalDate from = LocalDate.now().minusDays(Math.max(days - 1, 1));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", days);
        data.put("submissions", jdbcTemplate.queryForList(
                "SELECT DATE(create_time) AS day, COUNT(*) AS cnt FROM submission " +
                        "WHERE create_time >= ? GROUP BY DATE(create_time) ORDER BY day ASC",
                from.atStartOfDay()));
        data.put("judgeFailed", jdbcTemplate.queryForList(
                "SELECT DATE(create_time) AS day, COUNT(*) AS cnt FROM judge_task " +
                        "WHERE status = 'FAILED' AND create_time >= ? GROUP BY DATE(create_time) ORDER BY day ASC",
                from.atStartOfDay()));
        data.put("ai", aiUsageQueryService.selectDailyTrend(
                from.atStartOfDay(), LocalDate.now().plusDays(1).atStartOfDay()));
        return Result.success(data);
    }
}
