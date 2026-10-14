package top.everblue.oj.ai.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.everblue.oj.ai.entity.AiHintTrace;
import top.everblue.oj.ai.mapper.AiHintTraceMapper;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hint 解锁轨迹异步落库：提交即返回，落库失败仅 WARN 不抛出。
 */
@ExtendWith(MockitoExtension.class)
class AiHintTraceRecorderTest {

    @Mock
    private AiHintTraceMapper mapper;
    @Mock
    private ExecutorService executor;

    @Test
    void recordSubmitsTaskAndPersistsTrace() {
        AiHintTraceRecorder recorder = new AiHintTraceRecorder(mapper, executor);
        when(mapper.insert(any(AiHintTrace.class))).thenReturn(1);

        recorder.record(1L, 10L, 3, AiHintTrace.SOURCE_USER);

        // 异步任务已提交
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(taskCaptor.capture());
        // 手动执行任务（等价于 executor 异步执行）
        taskCaptor.getValue().run();

        ArgumentCaptor<AiHintTrace> traceCaptor = ArgumentCaptor.forClass(AiHintTrace.class);
        verify(mapper).insert(traceCaptor.capture());
        AiHintTrace trace = traceCaptor.getValue();
        assertEquals(1L, trace.getUserId());
        assertEquals(10L, trace.getProblemId());
        assertEquals(3, trace.getLevel());
        assertEquals(AiHintTrace.SOURCE_USER, trace.getSource());
    }

    @Test
    void recordFallsBackToUserSourceWhenNull() {
        AiHintTraceRecorder recorder = new AiHintTraceRecorder(mapper, executor);
        recorder.record(2L, 20L, 2, null);
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(taskCaptor.capture());
        taskCaptor.getValue().run();

        ArgumentCaptor<AiHintTrace> traceCaptor = ArgumentCaptor.forClass(AiHintTrace.class);
        verify(mapper).insert(traceCaptor.capture());
        assertEquals(AiHintTrace.SOURCE_USER, traceCaptor.getValue().getSource());
    }

    @Test
    void recordSwallowsInsertFailure() {
        AiHintTraceRecorder recorder = new AiHintTraceRecorder(mapper, executor);
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(AiHintTrace.class));
        recorder.record(1L, 10L, 4, AiHintTrace.SOURCE_USER);
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(taskCaptor.capture());
        // 落库失败不能向外传播（主链路是 Redis，轨迹失败仅 WARN）
        assertDoesNotThrow(taskCaptor.getValue()::run);
    }
}
