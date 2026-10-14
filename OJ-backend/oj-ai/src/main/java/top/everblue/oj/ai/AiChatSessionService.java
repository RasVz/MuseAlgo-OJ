package top.everblue.oj.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import top.everblue.oj.ai.entity.AiHintTrace;
import top.everblue.oj.ai.hint.HintLadderGuide;
import top.everblue.oj.ai.usage.AiHintTraceRecorder;
import top.everblue.oj.api.ai.HintTraceQuery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 助教会话记忆：对话历史存在 Redis，key = ai:chat:{userId}:{problemId}。
 * <p>
 * LLM API 是无状态的（服务端不记上下文），"记忆"由应用负责：
 * 存历史、拼回 messages、限长，三个职责都在这里。
 * </p>
 * <p>
 * Hint Ladder 解锁级别存在 Redis（key = ai:hint:{userId}:{problemId}，TTL 7 天），
 * 解锁动作同时异步落 {@code ai_hint_trace} 轨迹表（权威历史），Redis 仅是当前级别缓存。
 * </p>
 */
@Service
public class AiChatSessionService {

    private static final String KEY_PREFIX = "ai:chat:";
    private static final String HINT_KEY_PREFIX = "ai:hint:";

    /** 历史保留时长：覆盖学生一天内的连续答疑，到期自动清理 */
    private static final Duration TTL = Duration.ofHours(24);

    /** Hint 解锁级别缓存保留时长：7 天，缓解学生短期重刷丢失（权威在轨迹表，可重建） */
    private static final Duration HINT_TTL = Duration.ofDays(7);

    /** 最多保留的消息条数：一条 user + 一条 assistant = 一轮，即最近 10 轮 */
    private static final int MAX_MESSAGES = 20;

    private final StringRedisTemplate redisTemplate;
    private final AiHintTraceRecorder hintTraceRecorder;
    private final HintTraceQuery hintTraceQuery;

    public AiChatSessionService(StringRedisTemplate redisTemplate,
                                AiHintTraceRecorder hintTraceRecorder,
                                HintTraceQuery hintTraceQuery) {
        this.redisTemplate = redisTemplate;
        this.hintTraceRecorder = hintTraceRecorder;
        this.hintTraceQuery = hintTraceQuery;
    }

    /** 读出某个学生针对某道题的完整历史（按时间顺序） */
    public List<JSONObject> loadHistory(Long userId, Long problemId) {
        String raw = redisTemplate.opsForValue().get(key(userId, problemId));
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        return new JSONArray(raw).toList(JSONObject.class);
    }

    /** 追加新的一轮（学生提问 + AI 回复），并截断到最近 MAX_MESSAGES 条 */
    public void appendTurn(Long userId, Long problemId, String userContent, String assistantContent) {
        List<JSONObject> history = loadHistory(userId, problemId);
        history.add(message("user", userContent));
        history.add(message("assistant", assistantContent));
        if (history.size() > MAX_MESSAGES) {
            history = new ArrayList<>(history.subList(history.size() - MAX_MESSAGES, history.size()));
        }
        redisTemplate.opsForValue().set(key(userId, problemId), new JSONArray(history).toString(), TTL);
    }

    /** 当前已解锁的引导级别（默认 L1；0 = 学生还没主动使用阶梯） */
    public int getHintLevel(Long userId, Long problemId) {
        String raw = redisTemplate.opsForValue().get(hintKey(userId, problemId));
        if (raw == null || raw.isBlank()) {
            return HintLadderGuide.MIN_LEVEL;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return HintLadderGuide.MIN_LEVEL;
        }
    }

    /**
     * 成功获得第 currentLevel 级引导后，解锁下一级（只升不降，封顶 L4）。
     * Redis 更新是主链路；轨迹落库异步执行，失败仅 WARN 不影响解锁。
     */
    public void unlockNextHintLevel(Long userId, Long problemId, int currentLevel) {
        int next = Math.max(getHintLevel(userId, problemId),
                Math.min(currentLevel + 1, HintLadderGuide.MAX_LEVEL));
        redisTemplate.opsForValue().set(hintKey(userId, problemId), String.valueOf(next), HINT_TTL);
        hintTraceRecorder.record(userId, problemId, next, AiHintTrace.SOURCE_USER);
    }

    /**
     * 批量查询一组学生对某题的最高解锁级别（Redis 当前值 ∪ 轨迹表历史值，取大者）。
     * <p>
     * 轨迹表是权威历史：Redis 可能因 TTL 过期或刷新丢失而缺值，此时回落到轨迹表；
     * 两者都无记录的学生按未解锁（L1）处理，与单用户 {@link #getHintLevel} 语义一致。
     * </p>
     */
    public Map<Long, Integer> batchHintLevels(Long problemId, Collection<Long> userIds) {
        Map<Long, Integer> merged = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return merged;
        }
        // Redis 当前值（可能缺失，不能作为权威）
        for (Long userId : userIds) {
            merged.put(userId, getHintLevel(userId, problemId));
        }
        // 轨迹表历史最高级合并：谁高取谁
        hintTraceQuery.maxLevelByProblem(problemId, userIds)
                .forEach((userId, level) -> merged.merge(userId, level, Math::max));
        return merged;
    }

    private static JSONObject message(String role, String content) {
        JSONObject msg = new JSONObject();
        msg.set("role", role);
        msg.set("content", content);
        return msg;
    }

    private static String key(Long userId, Long problemId) {
        return KEY_PREFIX + userId + ":" + problemId;
    }

    private static String hintKey(Long userId, Long problemId) {
        return HINT_KEY_PREFIX + userId + ":" + problemId;
    }
}
