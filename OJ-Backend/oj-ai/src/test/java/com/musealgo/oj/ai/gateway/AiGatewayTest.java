package com.musealgo.oj.ai.gateway;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.musealgo.oj.ai.dto.AiCompletionResult;
import com.musealgo.oj.ai.dto.AiStreamResult;
import com.musealgo.oj.ai.runtime.AiRuntimeConfigResolver;
import com.musealgo.oj.ai.runtime.AiServiceSnapshot;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiGatewayTest {

    @Mock
    private AiRuntimeConfigResolver resolver;
    @Mock
    private AiProvider first;
    @Mock
    private AiProvider second;

    private AiGateway gateway;

    @BeforeEach
    void setUp() {
        lenient().when(first.protocol()).thenReturn(AiServiceSnapshot.PROTOCOL_CHAT_COMPLETIONS);
        lenient().when(second.protocol()).thenReturn(AiServiceSnapshot.PROTOCOL_CHAT_COMPLETIONS);
        gateway = new AiGateway(List.of(first, second), new AiResponseCache(), resolver);
    }

    @Test
    void managedCallsExactlyOneServiceAndNeverFailsOver() {
        AiServiceSnapshot snapshot = managed(1L, 1, "model-a");
        when(resolver.resolveCurrent()).thenReturn(snapshot);
        when(first.complete(any(), any(), anyBoolean()))
                .thenReturn(AiCompletionResult.failure(snapshot.source(), "NETWORK", "down"));

        AiCompletionResult result = gateway.completeDetailed(messages("unique-managed"), false);

        assertFalse(result.success());
        verify(first, times(1)).complete(any(), any(), anyBoolean());
        verify(second, never()).complete(any(), any(), anyBoolean());
    }

    @Test
    void legacyKeepsOrderedProviderFallback() {
        AiServiceSnapshot snapshot = legacy();
        when(resolver.resolveCurrent()).thenReturn(snapshot);
        when(first.complete(any(), any(), anyBoolean()))
                .thenReturn(AiCompletionResult.failure(snapshot.source(), "NETWORK", "down"));
        when(second.complete(any(), any(), anyBoolean()))
                .thenReturn(AiCompletionResult.success("legacy-ok", null, snapshot.source()));

        AiCompletionResult result = gateway.completeDetailed(messages("legacy"), false);

        assertTrue(result.success());
        assertEquals("legacy-ok", result.content());
        verify(first).complete(any(), any(), anyBoolean());
        verify(second).complete(any(), any(), anyBoolean());
    }

    @Test
    void cacheIsIsolatedByServiceVersionAndRefreshIsImmediate() {
        JSONArray messages = messages("same-question");
        AiServiceSnapshot v1 = managed(1L, 1, "model-a");
        AiServiceSnapshot v2 = managed(2L, 5, "model-b");
        when(resolver.resolveCurrent()).thenReturn(v1, v1, v2);
        when(first.complete(any(), any(), anyBoolean()))
                .thenReturn(AiCompletionResult.success("answer-a", null, v1.source()))
                .thenReturn(AiCompletionResult.success("answer-b", null, v2.source()));

        assertEquals("answer-a", gateway.completeDetailed(messages, false).content());
        assertEquals("answer-a", gateway.completeDetailed(messages, false).content());
        assertEquals("answer-b", gateway.completeDetailed(messages, false).content());
        verify(first, times(2)).complete(any(), any(), anyBoolean());
    }

    @Test
    void offModeDoesNotCallAnyProvider() {
        when(resolver.resolveCurrent()).thenReturn(AiServiceSnapshot.off());

        AiCompletionResult result = gateway.completeDetailed(messages("off"), false);
        AiStreamResult stream = gateway.stream(messages("off"), ignored -> {
        });

        assertFalse(result.success());
        assertFalse(stream.success());
        assertEquals("OFF", result.source().runtimeMode());
        verify(first, never()).complete(any(), any(), anyBoolean());
        verify(first, never()).stream(any(), any(), any());
    }

    @Test
    void emittedStreamFailureDoesNotTryAnotherProvider() {
        AiServiceSnapshot snapshot = legacy();
        when(resolver.resolveCurrent()).thenReturn(snapshot);
        when(first.stream(any(), any(), any()))
                .thenReturn(AiStreamResult.failure(snapshot.source(), true, "NETWORK", "mid-stream"));

        AiStreamResult result = gateway.stream(messages("stream"), ignored -> {
        });

        assertFalse(result.success());
        assertTrue(result.emitted());
        verify(first).stream(any(), any(), any());
        verify(second, never()).stream(any(), any(), any());
    }

    private static AiServiceSnapshot managed(Long id, int version, String model) {
        return AiServiceSnapshot.managed(id, "service-" + id, "CHAT_COMPLETIONS",
                URI.create("https://api.example.com/v1/chat/completions"), model, "secret",
                version, false, true, 1024);
    }

    private static AiServiceSnapshot legacy() {
        return AiServiceSnapshot.legacy(URI.create("https://api.deepseek.com/v1/chat/completions"),
                "deepseek-chat", "legacy-key", false, 8192);
    }

    private static JSONArray messages(String text) {
        JSONArray messages = new JSONArray();
        JSONObject user = new JSONObject();
        user.set("role", "user");
        user.set("content", text);
        messages.add(user);
        return messages;
    }
}
