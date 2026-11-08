package com.musealgo.oj.judge;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.musealgo.oj.judge.infrastructure.Judge0EndpointPool;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JudgeServiceTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sendsConfiguredMemoryModeWhilePreservingResourceLimits(boolean perProcessMemoryLimit) throws Exception {
        AtomicReference<JSONObject> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/submissions", exchange -> {
            request.set(JSONUtil.parseObj(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = "{\"status\":{\"id\":3},\"stdout\":\"T0s=\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Judge0EndpointPool pool = mock(Judge0EndpointPool.class);
            when(pool.candidates()).thenReturn(List.of("http://127.0.0.1:" + server.getAddress().getPort()));
            when(pool.tryAcquire(anyLong())).thenReturn(true);
            JudgeService service = new JudgeService(pool);
            ReflectionTestUtils.setField(service, "languageIds", Map.of("cpp", 54));
            ReflectionTestUtils.setField(service, "perProcessMemoryLimit", perProcessMemoryLimit);

            assertEquals(3, service.judge("int main() {}", "cpp", "", null, 1.0, 262144).get("status"));
            assertEquals(perProcessMemoryLimit, request.get().getBool("enable_per_process_and_thread_memory_limit"));
            assertEquals(262144, request.get().getInt("memory_limit"));
            assertEquals(1.0, request.get().getDouble("cpu_time_limit"));
            assertEquals(true, request.get().getBool("enable_per_process_and_thread_time_limit"));
        } finally {
            server.stop(0);
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\":{}}", "{\"status\":{\"id\":0}}",
            "{\"status\":{\"id\":1}}", "{\"status\":{\"id\":2}}",
            "{\"status\":{\"id\":99}}"})
    void incompleteOrUnknownJudgeResponseIsInfrastructureFailure(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/submissions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var pool = mock(Judge0EndpointPool.class);
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            when(pool.candidates()).thenReturn(List.of(endpoint));
            when(pool.tryAcquire(anyLong())).thenReturn(true);
            var service = new JudgeService(pool);
            ReflectionTestUtils.setField(service, "languageIds", Map.of("cpp", 54));
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> service.judge("int main() {}", "cpp", "", null, 1.0, 262144));
            org.mockito.Mockito.verify(pool).onFailure(endpoint);
            org.mockito.Mockito.verify(pool).release();
        } finally { server.stop(0); }
    }

}
