package com.musealgo.oj.ai.gateway;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.musealgo.oj.ai.dto.AiCompletionResult;
import com.musealgo.oj.ai.dto.AiStreamResult;
import com.musealgo.oj.ai.network.AiEndpointGuard;
import com.musealgo.oj.ai.runtime.AiServiceSnapshot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCompletionsClientTest {

    private final ExecutorService serverPool = Executors.newCachedThreadPool();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        serverPool.shutdownNow();
    }

    @Test
    void legacyStreamingSendsConfiguredOutputLimitAndDetectsTruncation() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        start(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "data: {\"choices\":[{\"delta\":{\"content\":\"片段\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n");
        });
        var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        var result = client(4096,65536,2048).stream(AiServiceSnapshot.legacy(endpoint,"legacy","sk-test",false,1024),messages(), x -> {});
        assertFalse(result.success()); assertEquals("OUTPUT_LIMIT",result.errorType()); assertEquals("片段",result.content());
        assertTrue(body.get().contains("\"max_tokens\":1024"));
    }

    @Test
    void providerContentFilterIsNotReportedAsTokenLimit() throws Exception {
        start(exchange -> respond(exchange, 200, "data: {\"choices\":[{\"delta\":{\"content\":\"片段\"},\"finish_reason\":\"content_filter\"}]}\n\ndata: [DONE]\n\n"));
        var result = client(4096,65536,2048).stream(snapshot(false,true),messages(),x -> {});
        assertFalse(result.success()); assertEquals("CONTENT_FILTER",result.errorType());
    }

    @Test
    void cancellationPreservesPartialUsageWithoutWaitingForNextDelta() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"已收到\"}}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}\n\n".getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();
            try { release.await(10,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        var control = new AiStreamControl(text -> first.countDown());
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> client(4096,65536,2048).stream(snapshot(false,true),messages(),control));
            assertTrue(first.await(5,TimeUnit.SECONDS)); control.cancel();
            var cancelled = result.get(3,TimeUnit.SECONDS);
            assertEquals("CANCELLED",cancelled.errorType()); assertFalse(cancelled.success());
            assertEquals("已收到",cancelled.content());assertEquals(4,cancelled.usage().promptTokens());
        } finally { release.countDown();worker.shutdownNow(); }
    }

    @Test
    void completeSendsSnapshotAuthModelAndParamsAndParsesUsage() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        start(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"choices":[{"message":{"content":"OK"}}],
                     "usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}
                    """);
        });
        ChatCompletionsClient client = client(4096, 65536, 2048);

        AiCompletionResult result = client.complete(snapshot(false, true), messages(), false);

        assertTrue(result.success());
        assertEquals("OK", result.content());
        assertEquals(4, result.usage().promptTokens());
        assertEquals(2, result.usage().completionTokens());
        assertEquals("Bearer sk-test", auth.get());
        assertTrue(body.get().contains("\"model\":\"model-a\""));
        assertTrue(body.get().contains("\"max_tokens\":1024"));
        assertFalse(body.get().contains("response_format"));
        assertFalse(body.get().contains("sk-test"));
    }

    @Test
    void jsonCallSetsResponseFormat() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        start(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"choices":[{"message":{"content":"{\\"ok\\":true}"}}]}
                    """);
        });
        ChatCompletionsClient client = client(4096, 65536, 2048);

        AiCompletionResult result = client.complete(snapshot(true, true), messages(), true);

        assertTrue(result.success());
        assertTrue(body.get().contains("\"response_format\":{\"type\":\"json_object\"}"));
    }

    @Test
    void streamHandlesFragmentedSseAndTerminalUsage() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String payload = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n"
                    + "data: [DONE]\n";
            int split = payload.length() / 2;
            exchange.getResponseBody().write(payload.substring(0, split).getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            exchange.getResponseBody().write(payload.substring(split).getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        ChatCompletionsClient client = client(4096, 65536, 2048);
        List<String> chunks = new ArrayList<>();

        AiStreamResult result = client.stream(snapshot(false, true), messages(), chunks::add);

        assertTrue(result.success());
        assertEquals("你好", result.content());
        assertEquals(List.of("你", "好"), chunks);
        assertEquals(5, result.usage().totalTokens());
    }

    @Test
    void streamEofAfterPartialContentDoesNotReportSuccess() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"unfinished\"}}]}\n\n"
                            .getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        List<String> chunks = new ArrayList<>();

        AiStreamResult result = client(4096, 65536, 2048)
                .stream(snapshot(false, true), messages(), chunks::add);

        assertEquals(List.of("unfinished"), chunks);
        assertTrue(result.emitted());
        assertFalse(result.success(), "没有 DONE 或 finish_reason 的 EOF 不能作为完整回答");
        assertEquals("NETWORK", result.errorType());
    }

    @Test
    void streamAcceptsExplicitFinishReasonWithoutDoneSentinel() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write((
                    "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });

        AiStreamResult result = client(4096, 65536, 2048)
                .stream(snapshot(false, true), messages(), ignored -> { });

        assertTrue(result.success());
        assertEquals("OK", result.content());
    }

    @Test
    void unterminatedOversizedLineIsRejectedBeforeWaitingForMoreData() throws Exception {
        CountDownLatch releaseUpstream = new CountDownLatch(1);
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("data: " + "x".repeat(4096))
                    .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                releaseUpstream.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        try {
            AiStreamResult result = client(4096, 65536, 128)
                    .stream(snapshot(false, true), messages(), ignored -> { });
            assertFalse(result.success());
            assertEquals("RESPONSE_TOO_LARGE", result.errorType(),
                    "限制必须在读到换行前生效，而不是超时后才停止读取");
        } finally {
            releaseUpstream.countDown();
        }
    }

    @Test
    void mapsHttpErrorsWithoutLeakingUpstreamBody() throws Exception {
        assertHttpError(401, "AUTH_FAILED");
        assertHttpError(429, "QUOTA_EXCEEDED");
        assertHttpError(500, "MODEL_UNAVAILABLE");
    }

    @Test
    void malformedAndOversizedResponsesFailSafely() throws Exception {
        start(exchange -> respond(exchange, 200, "{not-json"));
        ChatCompletionsClient malformed = client(4096, 65536, 2048);
        AiCompletionResult invalid = malformed.complete(snapshot(false, true), messages(), false);
        assertFalse(invalid.success());
        assertEquals("INVALID_RESPONSE", invalid.errorType());

        if (server != null) {
            server.stop(0);
        }
        start(exchange -> respond(exchange, 200, "x".repeat(500)));
        ChatCompletionsClient tinyLimit = client(100, 65536, 2048);
        AiCompletionResult tooLarge = tinyLimit.complete(snapshot(false, true), messages(), false);
        assertFalse(tooLarge.success());
        assertEquals("RESPONSE_TOO_LARGE", tooLarge.errorType());
    }

    @Test
    void streamErrorChunkAndOversizedLineFailWithoutRetry() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("data: {\"error\":{\"message\":\"secret details\"}}\n")
                    .getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        ChatCompletionsClient client = client(4096, 65536, 2048);
        AiStreamResult failed = client.stream(snapshot(false, true), messages(), ignored -> {
        });
        assertFalse(failed.success());
        assertEquals("MODEL_UNAVAILABLE", failed.errorType());
        assertFalse(failed.errorMessage().contains("secret details"));

        server.stop(0);
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("data: " + "x".repeat(3000) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        ChatCompletionsClient restartedClient = client(4096, 65536, 2048);
        AiStreamResult oversized = restartedClient.stream(snapshot(false, true), messages(), ignored -> {
        });
        assertEquals("RESPONSE_TOO_LARGE", oversized.errorType());
    }

    @Test
    void redirectIsNotFollowed() throws Exception {
        AtomicReference<Integer> targetHits = new AtomicReference<>(0);
        start(exchange -> {
            if ("/v1/chat/completions".equals(exchange.getRequestURI().getPath())) {
                exchange.getResponseHeaders().add("Location", baseUrl() + "/other");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            } else {
                targetHits.updateAndGet(value -> value + 1);
                respond(exchange, 200, "{}");
            }
        });
        ChatCompletionsClient client = client(4096, 65536, 2048);

        AiCompletionResult result = client.complete(snapshot(false, true), messages(), false);

        assertFalse(result.success());
        assertEquals("REDIRECT_BLOCKED", result.errorType());
        assertEquals(0, targetHits.get());
    }

    @Test
    void clientCancellationPropagatesToAbortUpstreamRead() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n"
                    .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        ChatCompletionsClient client = client(4096, 65536, 2048);

        assertThrows(IllegalStateException.class, () ->
                client.stream(snapshot(false, true), messages(), chunk -> {
                    throw new IllegalStateException("client gone");
                }));
    }

    @Test
    void timeoutReturnsControlledType() throws Exception {
        start(exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });
        ChatCompletionsClient client = new ChatCompletionsClient(guard(), 1, 1, 2, 4096, 65536, 2048);

        AiCompletionResult result = client.complete(snapshot(false, true), messages(), false);

        assertFalse(result.success());
        assertEquals("TIMEOUT", result.errorType());
        assertNull(result.content());
    }

    private void assertHttpError(int status, String type) throws Exception {
        start(exchange -> respond(exchange, status, "{\"error\":\"upstream secret\"}"));
        ChatCompletionsClient client = client(4096, 65536, 2048);
        AiCompletionResult result = client.complete(snapshot(false, true), messages(), false);
        assertFalse(result.success());
        assertEquals(type, result.errorType());
        assertFalse(result.errorMessage().contains("upstream secret"));
        server.stop(0);
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", handler);
        server.createContext("/other", handler);
        server.setExecutor(serverPool);
        server.start();
    }

    private AiEndpointGuard guard() {
        return new AiEndpointGuard(baseUrl());
    }

    private ChatCompletionsClient client(long maxResponse, long maxStream, int maxLine) {
        return new ChatCompletionsClient(guard(), 1, 2, 3, maxResponse, maxStream, maxLine);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    private AiServiceSnapshot snapshot(boolean jsonMode, boolean includeUsage) {
        return AiServiceSnapshot.managed(1L, "测试服务", "CHAT_COMPLETIONS",
                URI.create(baseUrl()), "model-a", "sk-test", 7, jsonMode, includeUsage, 1024);
    }

    private static JSONArray messages() {
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.set("role", "system");
        system.set("content", "probe");
        messages.add(system);
        JSONObject user = new JSONObject();
        user.set("role", "user");
        user.set("content", "OK");
        messages.add(user);
        return messages;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
