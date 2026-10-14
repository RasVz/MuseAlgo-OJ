package top.everblue.oj.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import top.everblue.oj.ai.entity.AiHintTrace;
import top.everblue.oj.ai.usage.AiHintTraceRecorder;
import top.everblue.oj.api.ai.HintTraceQuery;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hint Ladder 解锁语义（Redis 主链路）+ 轨迹落库触发 + 批量合并查询。
 * <p>
 * 服务端强制规则：只升不降、封顶 L4；解锁即写轨迹（异步，失败不影响主链路）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class AiChatSessionServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private AiHintTraceRecorder hintTraceRecorder;
    @Mock
    private HintTraceQuery hintTraceQuery;

    private AiChatSessionService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new AiChatSessionService(redisTemplate, hintTraceRecorder, hintTraceQuery);
    }

    // ======================= 解锁语义 =======================

    @Test
    void getHintLevelReturnsMinWhenNotUnlocked() {
        when(valueOps.get("ai:hint:1:10")).thenReturn(null);
        assertEquals(1, service.getHintLevel(1L, 10L));
    }

    @Test
    void unlockNextHintLevelOnlyGoesUp() {
        // 已解锁 L3，请求 L2 级引导成功 → 不应降级，仍写 L3
        when(valueOps.get("ai:hint:1:10")).thenReturn("3");
        service.unlockNextHintLevel(1L, 10L, 2);
        verify(valueOps).set("ai:hint:1:10", "3", Duration.ofDays(7));
        verify(hintTraceRecorder).record(1L, 10L, 3, AiHintTrace.SOURCE_USER);
    }

    @Test
    void unlockNextHintLevelCapsAtMaxLevel() {
        when(valueOps.get("ai:hint:1:10")).thenReturn("4");
        service.unlockNextHintLevel(1L, 10L, 4);
        verify(valueOps).set("ai:hint:1:10", "4", Duration.ofDays(7));
        verify(hintTraceRecorder).record(1L, 10L, 4, AiHintTrace.SOURCE_USER);
    }

    @Test
    void unlockNextHintLevelWritesTraceWithNext() {
        when(valueOps.get("ai:hint:1:10")).thenReturn("1");
        service.unlockNextHintLevel(1L, 10L, 1);
        verify(valueOps).set("ai:hint:1:10", "2", Duration.ofDays(7));
        verify(hintTraceRecorder).record(1L, 10L, 2, AiHintTrace.SOURCE_USER);
    }

    // ======================= 批量合并（Redis ∪ 轨迹表） =======================

    @Test
    void batchHintLevelsMergesRedisWithTraceHistoryTakingMax() {
        when(valueOps.get("ai:hint:1:10")).thenReturn("3");
        when(valueOps.get("ai:hint:2:10")).thenReturn(null);
        when(valueOps.get("ai:hint:3:10")).thenReturn(null);
        when(hintTraceQuery.maxLevelByProblem(eq(10L), any()))
                .thenReturn(Map.of(1L, 4, 2L, 2));

        Map<Long, Integer> levels = service.batchHintLevels(10L, List.of(1L, 2L, 3L));

        assertEquals(4, levels.get(1L), "Redis L3 与轨迹 L4 取高");
        assertEquals(2, levels.get(2L), "Redis 缺失回落到轨迹表");
        assertEquals(1, levels.get(3L), "两者皆无按未解锁 L1");
    }

    @Test
    void batchHintLevelsReturnsEmptyForEmptyUsers() {
        assertEquals(Map.of(), service.batchHintLevels(10L, List.of()));
        verify(hintTraceQuery, never()).maxLevelByProblem(any(), any());
    }
}
