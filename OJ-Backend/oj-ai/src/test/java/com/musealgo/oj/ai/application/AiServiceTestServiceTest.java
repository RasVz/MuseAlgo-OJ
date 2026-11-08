package com.musealgo.oj.ai.application;

import cn.hutool.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import com.musealgo.oj.ai.adminai.AiAdminException;
import com.musealgo.oj.ai.adminai.AiServiceAdminDtos;
import com.musealgo.oj.ai.adminai.AiServiceProbe;
import com.musealgo.oj.ai.dto.AiCompletionResult;
import com.musealgo.oj.ai.dto.AiStreamResult;
import com.musealgo.oj.ai.entity.AiServiceConfig;
import com.musealgo.oj.ai.gateway.ChatCompletionsClient;
import com.musealgo.oj.ai.mapper.AiServiceConfigMapper;
import com.musealgo.oj.ai.runtime.AiServiceSnapshot;
import com.musealgo.oj.ai.crypto.AiKeyCipher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiServiceTestServiceTest {

    @Mock
    private AiServiceCatalogService catalogService;
    @Mock
    private AiServiceConfigMapper serviceMapper;
    @Mock
    private AiKeyCipher keyCipher;
    @Mock
    private ChatCompletionsClient defaultClient;
    @Mock
    private ChatCompletionsClient shortTimeoutClient;
    @Mock
    private PlatformTransactionManager transactionManager;

    private AiServiceTestService testService;

    @BeforeEach
    void setUp() {
        when(defaultClient.withTimeouts(5, 20, 30)).thenReturn(shortTimeoutClient);
        stubSuccessfulStream(shortTimeoutClient);
        AiServiceProbe probe = new AiServiceProbe(defaultClient);
        testService = new AiServiceTestService(catalogService, serviceMapper, keyCipher,
                probe, defaultClient, transactionManager);
    }

    @Test
    void candidateProbeUsesTheShortTimeoutClient() {
        doAnswer(invocation -> {
            AiServiceSnapshot snapshot = invocation.getArgument(0);
            return AiCompletionResult.success("OK", null, snapshot.source());
        }).when(shortTimeoutClient).complete(any(), any(JSONArray.class), anyBoolean());
        AiServiceAdminDtos.TestReport report = testService.probeCandidate(candidate(71L, 3), "test-key");

        assertTrue(report.passed());
        verify(defaultClient).withTimeouts(5, 20, 30);
        verify(shortTimeoutClient).complete(any(), any(JSONArray.class), eq(false));
        verify(shortTimeoutClient).stream(any(), any(JSONArray.class), any());
        verify(defaultClient, never()).complete(any(), any(JSONArray.class), anyBoolean());
        verify(defaultClient, never()).stream(any(), any(JSONArray.class), any());
    }

    @Test
    void savedAndCandidateTestsForSameServiceAreDeduplicatedAcrossActors() throws Exception {
        CountDownLatch firstProbeEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstProbe = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        stubFirstCompletionBlocks(shortTimeoutClient, calls, firstProbeEntered, releaseFirstProbe);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<AiServiceAdminDtos.TestReport> candidateRun = executor.submit(
                () -> testService.probeCandidate(candidate(71L, 5), "candidate-key"));
        try {
            assertTrue(firstProbeEntered.await(2, TimeUnit.SECONDS), "candidate probe did not reach the upstream call");

            AiAdminException duplicate = assertThrows(AiAdminException.class,
                    () -> testService.test(71L, 4, 900L));

            assertEquals(409, duplicate.getCode());
            verify(catalogService, never()).requireService(71L);
        } finally {
            releaseFirstProbe.countDown();
            candidateRun.get(2, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    void candidateProbesShareTheFourSlotLimit() throws Exception {
        CountDownLatch fourProbesEntered = new CountDownLatch(4);
        CountDownLatch releaseProbes = new CountDownLatch(1);
        stubBlockingCompletions(shortTimeoutClient, fourProbesEntered, releaseProbes);

        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<Future<AiServiceAdminDtos.TestReport>> inFlight = new ArrayList<>();
        try {
            for (long serviceId = 100; serviceId < 104; serviceId++) {
                AiServiceConfig candidate = candidate(serviceId, 1);
                inFlight.add(executor.submit(() -> testService.probeCandidate(candidate, "test-key")));
            }
            assertTrue(fourProbesEntered.await(2, TimeUnit.SECONDS), "four allowed probes did not start");

            AiServiceConfig fifth = candidate(104L, 1);
            Future<AiAdminException> overflow = executor.submit(() -> assertThrows(AiAdminException.class,
                    () -> testService.probeCandidate(fifth, "test-key")));
            AiAdminException rejected = overflow.get(2, TimeUnit.SECONDS);

            assertEquals(429, rejected.getCode());
        } finally {
            releaseProbes.countDown();
            for (Future<AiServiceAdminDtos.TestReport> run : inFlight) {
                run.get(2, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
        }
    }

    @Test
    void candidateProbeReleasesItsSlotAndServiceKeyWhenProbeThrows() {
        stubFailThenSucceed(shortTimeoutClient);

        assertThrows(IllegalStateException.class,
                () -> testService.probeCandidate(candidate(81L, 1), "test-key"));

        AiServiceAdminDtos.TestReport retry = testService.probeCandidate(candidate(81L, 1), "test-key");

        assertTrue(retry.passed());
    }

    private static void stubSuccessfulStream(ChatCompletionsClient client) {
        doAnswer(invocation -> {
            AiServiceSnapshot snapshot = invocation.getArgument(0);
            return AiStreamResult.success("OK", null, snapshot.source(), true);
        }).when(client).stream(any(), any(JSONArray.class), any());
    }

    private static void stubFirstCompletionBlocks(ChatCompletionsClient client, AtomicInteger calls,
                                                   CountDownLatch entered, CountDownLatch release) {
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test probe was not released");
                }
            }
            AiServiceSnapshot snapshot = invocation.getArgument(0);
            return AiCompletionResult.success("OK", null, snapshot.source());
        }).when(client).complete(any(), any(JSONArray.class), anyBoolean());
    }

    private static void stubBlockingCompletions(ChatCompletionsClient client,
                                                 CountDownLatch entered, CountDownLatch release) {
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test probes were not released");
            }
            AiServiceSnapshot snapshot = invocation.getArgument(0);
            return AiCompletionResult.success("OK", null, snapshot.source());
        }).when(client).complete(any(), any(JSONArray.class), anyBoolean());
    }

    private static void stubFailThenSucceed(ChatCompletionsClient client) {
        doThrow(new IllegalStateException("probe failure"))
                .doAnswer(invocation -> {
                    AiServiceSnapshot snapshot = invocation.getArgument(0);
                    return AiCompletionResult.success("OK", null, snapshot.source());
                }).when(client).complete(any(), any(JSONArray.class), anyBoolean());
    }

    private static AiServiceConfig candidate(Long id, int version) {
        AiServiceConfig config = new AiServiceConfig();
        config.setId(id);
        config.setName("Test service " + id);
        config.setProtocol(AiServiceConfig.PROTOCOL_CHAT_COMPLETIONS);
        config.setApiUrl("https://example.test/v1/chat/completions");
        config.setModel("test-model");
        config.setVersion(version);
        config.setJsonMode(false);
        config.setStreamIncludeUsage(false);
        config.setMaxTokens(1024);
        return config;
    }

}
