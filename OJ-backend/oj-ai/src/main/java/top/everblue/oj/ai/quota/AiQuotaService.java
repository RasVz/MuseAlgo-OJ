package top.everblue.oj.ai.quota;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import top.everblue.oj.ai.config.DynamicAiConfig;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * AI 每日 token 配额（Redis 计数，按自然日重置）。
 *
 * <h3>设计</h3>
 * <ul>
 *   <li>key = {@code ai:quota:{userId}:{yyyy-MM-dd}}，INCRBY 累加，首次写入挂 48h TTL 兜底清理</li>
 *   <li>先检查后调用：余额不足直接拒绝，不消耗模型调用；调用成功后按实际（或估算）token 入账</li>
 *   <li>教师/管理员默认豁免（备课与调试场景），可用配置关闭豁免</li>
 * </ul>
 *
 * <h3>配置</h3>
 * <ul>
 *   <li>{@code oj.ai.quota.enabled} 默认 true</li>
 *   <li>{@code oj.ai.quota.daily-tokens} 学生每日上限，默认 30000</li>
 *   <li>{@code oj.ai.quota.exempt-teacher-admin} 默认 true</li>
 * </ul>
 *
 * <h3>精度说明</h3>
 * token 计数优先使用 API 返回的 usage；流式异常中断时退化为字符估算（约 2 字符/token）。
 * 配额的意义是"防止失控"而非"精确计费"，估算偏差可接受。
 */
@Slf4j
@Service
public class AiQuotaService {

    private static final String KEY_PREFIX = "ai:quota:";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** TTL 略大于一天：保证跨午夜后旧 key 自动消失，不依赖主动清理 */
    private static final Duration KEY_TTL = Duration.ofHours(48);

    private final StringRedisTemplate redisTemplate;

    private final DynamicAiConfig config;

    public AiQuotaService(StringRedisTemplate redisTemplate, DynamicAiConfig config) {
        this.redisTemplate = redisTemplate;
        this.config = config;
    }

    /**
     * 调用前检查。豁免角色（教师/管理员）与总开关在 Controller 判定后传入。
     *
     * @param privileged 是否豁免角色（角色判定必须在请求线程完成，异步线程无 SecurityContext）
     * @return true = 允许调用；false = 今日额度已用完
     */
    public boolean tryAcquire(Long userId, boolean privileged) {
        boolean enabled = config.quotaEnabled();
        boolean exemptTeacherAdmin = config.exemptTeacherAdmin();
        long dailyTokens = config.dailyTokens();
        if (!enabled || (exemptTeacherAdmin && privileged)) {
            return true;
        }
        try {
            long used = currentUsage(userId);
            if (used >= dailyTokens) {
                log.info("【AI配额】用户今日额度已用完 userId={}, used={}/{}", userId, used, dailyTokens);
                return false;
            }
            return true;
        } catch (Exception e) {
            // Redis 故障时放行：配额是成本控制手段，不应成为可用性瓶颈
            log.warn("【AI配额】检查失败，放行本次请求 userId={}, err={}", userId, e.getMessage());
            return true;
        }
    }

    /** 调用完成后入账实际 token 用量 */
    public void consume(Long userId, int tokens, boolean privileged) {
        boolean enabled = config.quotaEnabled();
        boolean exemptTeacherAdmin = config.exemptTeacherAdmin();
        if (!enabled || tokens <= 0 || (exemptTeacherAdmin && privileged)) {
            return;
        }
        try {
            String key = key(userId);
            redisTemplate.opsForValue().increment(key, tokens);
            redisTemplate.expire(key, KEY_TTL);
        } catch (Exception e) {
            log.warn("【AI配额】入账失败 userId={}, tokens={}, err={}", userId, tokens, e.getMessage());
        }
    }

    /** 今日已用额度（监控/前端展示可用） */
    public long currentUsage(Long userId) {
        String raw = redisTemplate.opsForValue().get(key(userId));
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 今日总额度（配合 currentUsage 做展示） */
    public long dailyLimit() {
        return config.dailyTokens();
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId + ":" + LocalDate.now().format(DAY);
    }
}
