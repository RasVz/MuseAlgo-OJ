package top.everblue.oj.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.ai.entity.AiUsage;
import top.everblue.oj.ai.query.AiUsageQueryService;
import top.everblue.oj.ai.config.DynamicAiConfig;
import top.everblue.oj.api.system.SystemConfigCommand;
import top.everblue.oj.api.user.UserDisplayInfo;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.annotation.AuditLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * AI 管理台（管理员）：用量统计、趋势、明细、用户排行、配置概览。
 * 数据源全部是 ai_usage 流水 + 配置属性，只读不改。
 */
@Tag(name = "AI 管理", description = "AI 用量统计与配置概览")
@RestController
@RequestMapping("/admin/ai")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AiAdminController {

    /** DeepSeek deepseek-chat 近似定价：¥2 / 百万 token（展示用估算值） */
    private static final double COST_PER_MILLION_TOKENS = 2.0;

    private final AiUsageQueryService aiUsageQueryService;
    private final UserQuery userQuery;
    private final DynamicAiConfig aiConfig;
    private final SystemConfigCommand systemConfigCommand;
    private final StringRedisTemplate redisTemplate;

    private static final String QUOTA_KEY_PREFIX = "ai:quota:";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Duration KEY_TTL = Duration.ofHours(48);

    /** 允许后台修改的配置键 */
    private static final java.util.Set<String> CONFIG_KEYS = java.util.Set.of(
            "ai.quota.enabled", "ai.quota.daily-tokens", "ai.quota.exempt-teacher-admin",
            "ai.contest-guard.enabled", "ai.contest-guard.cache-seconds",
            "ai.model", "ai.max-tokens", "ai.json-mode");

    @Operation(summary = "用量总览")
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam(defaultValue = "7") int days) {
        LocalDateTime[] window = window(days);
        List<Map<String, Object>> rows = aiUsageQueryService.selectStatusSummary(window[0], window[1]);
        long calls = 0, tokens = 0, success = 0, fail = 0, quotaBlocked = 0, contestBlocked = 0;
        for (Map<String, Object> row : rows) {
            long cnt = ((Number) row.get("cnt")).longValue();
            calls += cnt;
            tokens += ((Number) row.get("tokens")).longValue();
            switch (String.valueOf(row.get("status"))) {
                case "SUCCESS" -> success = cnt;
                case "FAIL" -> fail = cnt;
                case "QUOTA_REJECTED" -> quotaBlocked = cnt;
                case "CONTEST_BLOCKED" -> contestBlocked = cnt;
                default -> { }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", days);
        data.put("calls", calls);
        data.put("tokens", tokens);
        data.put("success", success);
        data.put("fail", fail);
        data.put("failRate", calls == 0 ? 0 : Math.round(fail * 1000.0 / calls) / 10.0);
        data.put("quotaBlocked", quotaBlocked);
        data.put("contestBlocked", contestBlocked);
        data.put("estimatedCostYuan", Math.round(tokens / 1_000_000.0 * COST_PER_MILLION_TOKENS * 100) / 100.0);
        return Result.success(data);
    }

    @Operation(summary = "按日趋势")
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "7") int days) {
        LocalDateTime[] window = window(days);
        return Result.success(aiUsageQueryService.selectDailyTrend(window[0], window[1]));
    }

    @Operation(summary = "用量明细（分页筛选）")
    @GetMapping("/logs")
    public Result<Page<AiUsage>> logs(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        LambdaQueryWrapper<AiUsage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(userId != null, AiUsage::getUserId, userId)
                .eq(StringUtils.hasText(scene), AiUsage::getScene, scene)
                .eq(StringUtils.hasText(status), AiUsage::getStatus, status)
                .ge(StringUtils.hasText(start), AiUsage::getCreatedAt, parseTime(start))
                .le(StringUtils.hasText(end), AiUsage::getCreatedAt, parseEndTime(end))
                .orderByDesc(AiUsage::getCreatedAt);
        return Result.success(aiUsageQueryService.selectPage(new Page<>(page, size), wrapper));
    }

    @Operation(summary = "用户 token 消耗排行")
    @GetMapping("/quota-ranking")
    public Result<List<Map<String, Object>>> quotaRanking(@RequestParam(defaultValue = "7") int days) {
        LocalDateTime[] window = window(days);
        List<Map<String, Object>> rows = aiUsageQueryService.selectUserRanking(window[0], window[1], 20);
        Set<Long> userIds = rows.stream()
                .map(r -> ((Number) r.get("user_id")).longValue()).collect(Collectors.toSet());
        Map<Long, UserDisplayInfo> userMap = userQuery.findDisplayInfoBatch(userIds);
        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> row : rows) {
            Long userId = ((Number) row.get("user_id")).longValue();
            UserDisplayInfo info = userMap.get(userId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", rank++);
            item.put("userId", userId);
            item.put("username", info != null
                    ? (info.nickname() != null && !info.nickname().isBlank() ? info.nickname() : info.username())
                    : "用户" + userId);
            item.put("calls", ((Number) row.get("calls")).longValue());
            item.put("tokens", ((Number) row.get("tokens")).longValue());
            String quotaKey = QUOTA_KEY_PREFIX + userId + ":" + LocalDate.now().format(DAY);
            String rawUsed = redisTemplate.opsForValue().get(quotaKey);
            long usedToday = rawUsed == null ? 0 : Long.parseLong(rawUsed);
            long dailyLimit = aiConfig.dailyTokens();
            item.put("usedToday", usedToday);
            item.put("usageRate", dailyLimit <= 0 ? 0
                    : Math.round(usedToday * 1000.0 / dailyLimit) / 10.0);
            result.add(item);
        }
        return Result.success(result);
    }

    @Operation(summary = "AI 配置概览（只读）")
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quotaEnabled", aiConfig.quotaEnabled());
        data.put("dailyTokens", aiConfig.dailyTokens());
        data.put("exemptTeacherAdmin", aiConfig.exemptTeacherAdmin());
        data.put("contestGuardEnabled", aiConfig.contestGuardEnabled());
        data.put("contestGuardCacheSeconds", aiConfig.contestGuardCacheSeconds());
        data.put("model", aiConfig.model());
        data.put("maxTokens", aiConfig.maxTokens());
        data.put("jsonMode", aiConfig.jsonMode());
        return Result.success(data);
    }

    @Operation(summary = "更新 AI 配置（写 system_config，秒级生效）")
    @AuditLog(module = "ai", action = "update_config")
    @PostMapping("/config")
    public Result<String> updateConfig(@RequestBody Map<String, String> body) {
        String key = body.get("key");
        String value = body.get("value");
        if (key == null || !CONFIG_KEYS.contains(key)) {
            return Result.error("不允许的配置键: " + key);
        }
        if (value == null) {
            return Result.error("配置值不能为空");
        }
        systemConfigCommand.set(key, value);
        return Result.success("配置已更新（秒级生效）");
    }

    @Operation(summary = "查询用户当日配额用量")
    @GetMapping("/quota/{userId}")
    public Result<Map<String, Object>> quotaUsage(@PathVariable Long userId) {
        String key = QUOTA_KEY_PREFIX + userId + ":" + LocalDate.now().format(DAY);
        String raw = redisTemplate.opsForValue().get(key);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("usedToday", raw == null ? 0 : Long.parseLong(raw));
        data.put("dailyLimit", aiConfig.dailyTokens());
        data.put("date", LocalDate.now().format(DAY));
        return Result.success(data);
    }

    @Operation(summary = "重置用户当日配额")
    @AuditLog(module = "ai", action = "reset_quota")
    @PostMapping("/quota/{userId}/reset")
    public Result<String> resetQuota(@PathVariable Long userId) {
        redisTemplate.delete(QUOTA_KEY_PREFIX + userId + ":" + LocalDate.now().format(DAY));
        return Result.success("已重置当日配额");
    }

    @Operation(summary = "手动设置用户当日已用配额")
    @AuditLog(module = "ai", action = "set_quota")
    @PostMapping("/quota/{userId}/set")
    public Result<String> setQuota(@PathVariable Long userId, @RequestBody Map<String, Object> body) {
        long used = body.get("used") == null ? 0 : Long.parseLong(String.valueOf(body.get("used")));
        String key = QUOTA_KEY_PREFIX + userId + ":" + LocalDate.now().format(DAY);
        if (used <= 0) {
            redisTemplate.delete(key);
        } else {
            redisTemplate.opsForValue().set(key, String.valueOf(used), KEY_TTL);
        }
        return Result.success("已设置（used=" + used + "）");
    }

    private static LocalDateTime[] window(int days) {
        LocalDateTime to = LocalDate.now().plusDays(1).atStartOfDay(); // 含今天
        LocalDateTime from = to.minusDays(Math.max(days, 1));
        return new LocalDateTime[]{from, to};
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null; // 条件参数会被立即求值，空值返回 null 而非 NPE
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return LocalDate.parse(value).atStartOfDay();
        }
    }

    private static LocalDateTime parseEndTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return LocalDate.parse(value).plusDays(1).atStartOfDay().minusNanos(1);
        }
    }
}
