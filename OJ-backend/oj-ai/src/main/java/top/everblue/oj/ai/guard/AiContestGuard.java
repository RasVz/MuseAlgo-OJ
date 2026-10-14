package top.everblue.oj.ai.guard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import top.everblue.oj.ai.config.DynamicAiConfig;
import top.everblue.oj.api.contest.RunningContestQuery;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 比赛防护栏：题目处于进行中比赛时，拦截 AI 答疑请求。
 *
 * <h3>判定链路</h3>
 * Redis 短时缓存（默认 60s）→ oj-api 的 {@link RunningContestQuery} 契约 →
 * oj-contest 的 problem_contest + contest 时间窗查询。
 *
 * <h3>豁免与降级</h3>
 * <ul>
 *   <li>教师/管理员默认豁免（命题人需要验证 AI 对比赛题的回答质量）；</li>
 *   <li>判定过程异常（DB/Redis 故障）时放行——防护栏是公平性手段，
 *       不应成为答疑功能的单点故障；比赛题目通常只占题库极小部分，短时放行风险可控。</li>
 * </ul>
 *
 * <h3>配置</h3>
 * <ul>
 *   <li>{@code oj.ai.contest-guard.enabled} 默认 true</li>
 *   <li>{@code oj.ai.contest-guard.cache-seconds} 判定结果缓存时长，默认 60</li>
 *   <li>{@code oj.ai.contest-guard.exempt-teacher-admin} 默认 true</li>
 * </ul>
 */
@Slf4j
@Service
public class AiContestGuard {

    private static final String KEY_PREFIX = "ai:contest-block:";

    private final RunningContestQuery runningContestQuery;
    private final StringRedisTemplate redisTemplate;

    private final DynamicAiConfig config;

    public AiContestGuard(RunningContestQuery runningContestQuery, StringRedisTemplate redisTemplate,
                          DynamicAiConfig config) {
        this.runningContestQuery = runningContestQuery;
        this.redisTemplate = redisTemplate;
        this.config = config;
    }

    /**
     * 判定本次提问是否应被拦截。
     *
     * @param privileged 是否豁免角色（须在请求线程判定后传入，异步线程无 SecurityContext）
     * @return true = 比赛进行中，应拦截
     */
    public boolean shouldBlock(Long problemId, boolean privileged) {
        boolean enabled = config.contestGuardEnabled();
        boolean exemptTeacherAdmin = config.exemptTeacherAdmin();
        long cacheSeconds = config.contestGuardCacheSeconds();
        if (!enabled || problemId == null || (exemptTeacherAdmin && privileged)) {
            return false;
        }
        try {
            String key = KEY_PREFIX + problemId;
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Boolean.parseBoolean(cached);
            }
            boolean running = runningContestQuery.hasRunningContestForProblem(problemId, LocalDateTime.now());
            redisTemplate.opsForValue().set(key, String.valueOf(running), Duration.ofSeconds(cacheSeconds));
            if (running) {
                log.info("【比赛防护】题目处于进行中比赛，AI 答疑拦截 problemId={}", problemId);
            }
            return running;
        } catch (Exception e) {
            log.warn("【比赛防护】判定失败，放行本次请求 problemId={}, err={}", problemId, e.getMessage());
            return false;
        }
    }
}
