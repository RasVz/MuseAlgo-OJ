package top.everblue.oj.judge;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import top.everblue.oj.judge.infrastructure.Judge0EndpointPool;

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
}
