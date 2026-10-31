package com.musealgo.oj.ai.usage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.musealgo.oj.ai.dto.AiCallSource;
import com.musealgo.oj.ai.entity.AiUsage;
import com.musealgo.oj.ai.mapper.AiUsageMapper;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiUsageRecorderTest {

    @Test
    void persistsActualServiceAndVersionSnapshot() {
        AiUsageMapper mapper = mock(AiUsageMapper.class);
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any());
        AiUsageRecorder recorder = new AiUsageRecorder(mapper, executor, new SimpleMeterRegistry());

        recorder.record(1L, 2L, "ASK", 10, 20, false, 123, "SUCCESS", null,
                new AiCallSource(9L, "教学主服务", "model-x", 7, "MANAGED"));

        ArgumentCaptor<AiUsage> captor = ArgumentCaptor.forClass(AiUsage.class);
        verify(mapper).insert(captor.capture());
        AiUsage usage = captor.getValue();
        assertEquals(9L, usage.getServiceId());
        assertEquals("教学主服务", usage.getServiceName());
        assertEquals("model-x", usage.getModel());
        assertEquals("MANAGED", usage.getRuntimeMode());
        assertEquals(7, usage.getConfigVersion());
        assertEquals(30, usage.getTotalTokens());
    }

    @Test
    void legacyCallerWithoutSourceKeepsOldNullColumns() {
        AiUsageMapper mapper = mock(AiUsageMapper.class);
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any());
        AiUsageRecorder recorder = new AiUsageRecorder(mapper, executor, new SimpleMeterRegistry());

        recorder.record(1L, null, "ASK", 1, 1, true, 5, "FAIL", "x");

        ArgumentCaptor<AiUsage> captor = ArgumentCaptor.forClass(AiUsage.class);
        verify(mapper).insert(captor.capture());
        assertNull(captor.getValue().getServiceId());
        assertNull(captor.getValue().getRuntimeMode());
    }
}
