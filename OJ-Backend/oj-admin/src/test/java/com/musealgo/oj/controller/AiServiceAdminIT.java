package com.musealgo.oj.controller;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.musealgo.oj.ai.adminai.AiAdminException;
import com.musealgo.oj.ai.adminai.AiServiceAdminDtos;
import com.musealgo.oj.ai.application.AiServiceCatalogService;
import com.musealgo.oj.ai.application.AiServiceTestService;
import com.musealgo.oj.ai.application.AiServiceUpdateService;
import com.musealgo.oj.ai.dto.AiCompletionResult;
import com.musealgo.oj.ai.entity.AiRuntimeConfig;
import com.musealgo.oj.ai.gateway.AiGateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 服务管理真实 MySQL 集成测试（隔离库 + 受控本地上游）。
 *
 * <p>不连接真实供应商；所有“API 请求”都发往进程内 HttpServer，Key 为测试值。
 * 覆盖密文存储、版本绑定、连接测试、原子切换、当前配置测试失败、缓存隔离、OFF 不外呼、
 * 运行中请求来源快照和并发切换。</p>
 */
@SpringBootTest(properties = {
        "oj.messaging.rabbitmq-enabled=false",
        "oj.judge.worker.enabled=false",
        "oj.judge.watchdog.enabled=false",
        "oj.monitoring.enabled=false"
})
@AutoConfigureMockMvc
class AiServiceAdminIT {

    private static final String DB_URL =
            "jdbc:mysql://127.0.0.1:3307/oj_ai_service_test?useUnicode=true&characterEncoding=UTF-8"
                    + "&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final Path KEY_FILE;
    private static final HttpServer UPSTREAM;
    private static final String UPSTREAM_URL;
    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> SLOW_ENTERED = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> SLOW_RELEASE = new AtomicReference<>();

    static {
        try {
            KEY_FILE = Files.createTempFile("oj-ai-master-", ".key");
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            Files.writeString(KEY_FILE, Base64.getEncoder().encodeToString(key), StandardCharsets.US_ASCII);

            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            UPSTREAM.createContext("/v1/chat/completions", AiServiceAdminIT::handleUpstream);
            UPSTREAM.setExecutor(Executors.newCachedThreadPool());
            UPSTREAM.start();
            UPSTREAM_URL = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort() + "/v1/chat/completions";
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "123456");
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> "6381");
        registry.add("oj.ai.crypto.key-file", KEY_FILE::toString);
        registry.add("oj.ai.network.private-targets", () -> UPSTREAM_URL);
        // 宿主环境可能带真实 DEEPSEEK_* 变量；集成测试必须强制隔离，LEGACY 分支也不得外呼。
        registry.add("ai.api-key", () -> "");
        registry.add("ai.api-url", () -> "http://127.0.0.1:9/v1/chat/completions");
        registry.add("ai.model", () -> "test-legacy-model");
    }

    @Autowired
    private AiServiceCatalogService catalogService;
    @Autowired
    private AiServiceTestService testService;
    @Autowired
    private AiServiceUpdateService updateService;
    @Autowired
    private AiGateway aiGateway;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM ai_service_config");
        jdbcTemplate.update("UPDATE ai_runtime_config SET mode='LEGACY', current_service_id=NULL, "
                + "version=version+1, updated_at=CURRENT_TIMESTAMP(3) WHERE id=1");
        REQUEST_COUNT.set(0);
        LAST_AUTH.set(null);
        SLOW_ENTERED.set(null);
        SLOW_RELEASE.set(null);
    }

    @AfterAll
    static void stopUpstream() throws IOException {
        UPSTREAM.stop(0);
        Files.deleteIfExists(KEY_FILE);
    }

    @Test
    void storesCiphertextTestsAndSwitchesWithoutServingStaleCache() throws Exception {
        String plaintext = "sk-integration-a";
        AiServiceAdminDtos.ServiceVo serviceA = create("教学主服务", "model-a", plaintext);
        assertEquals(1, serviceA.version());
        assertTrue(serviceA.hasApiKey());
        assertEquals("UNTESTED", serviceA.testState());

        String ciphertext = rawCipher(serviceA.id());
        assertNotEquals(plaintext, ciphertext);
        assertTrue(ciphertext.startsWith("v1:"));
        assertFalse(ciphertext.contains(plaintext));
        String serialized = objectMapper.writeValueAsString(serviceA);
        assertFalse(serialized.contains(plaintext));
        assertFalse(serialized.contains(ciphertext));
        assertFalse(serialized.contains("apiKeyEncrypted"));

        AiServiceAdminDtos.TestReport testA = testService.test(serviceA.id(), serviceA.version(), 1L);
        assertTrue(testA.passed(), () -> "report=" + testA);
        assertEquals(3, testA.checks().size());
        assertEquals("PASSED", jdbcTemplate.queryForObject(
                "SELECT last_test_status FROM ai_service_config WHERE id=?", String.class, serviceA.id()));

        int runtimeVersion = catalogService.runtimeView().runtimeVersion();
        updateService.activate(serviceA.id(), serviceA.version(), runtimeVersion, 1L);

        String question = "同一道题：" + System.nanoTime();
        AiCompletionResult answerA = aiGateway.completeDetailed(messages(question), false);
        assertTrue(answerA.success());
        assertEquals("answer:model-a", answerA.content());
        assertEquals(serviceA.id(), answerA.source().serviceId());
        assertEquals("MANAGED", answerA.source().runtimeMode());
        assertEquals(1, answerA.source().configVersion());

        AiServiceAdminDtos.ServiceVo serviceB = create("备选服务", "model-b", "sk-integration-b");
        assertTrue(testService.test(serviceB.id(), serviceB.version(), 1L).passed());
        int beforeSwitch = catalogService.runtimeView().runtimeVersion();
        updateService.activate(serviceB.id(), serviceB.version(), beforeSwitch, 1L);

        AiCompletionResult answerB = aiGateway.completeDetailed(messages(question), false);
        assertTrue(answerB.success());
        assertEquals("answer:model-b", answerB.content(), "切换服务后相同消息不得命中旧服务缓存");
        assertEquals(serviceB.id(), answerB.source().serviceId());

        assertEquals(2, aiServiceCount());
    }

    @Test
    void corruptedCiphertextFailsSafelyWithoutCallingAnotherSource() {
        AiServiceAdminDtos.ServiceVo service = create("损坏密文", "model-corrupt", "sk-corrupt");
        assertTrue(testService.test(service.id(), service.version(), 1L).passed());
        updateService.activate(service.id(), service.version(), catalogService.runtimeView().runtimeVersion(), 1L);
        jdbcTemplate.update("UPDATE ai_service_config SET api_key_encrypted='v1:AAAA:AAAA' WHERE id=?",
                service.id());
        int sentBefore = REQUEST_COUNT.get();

        AiCompletionResult result = aiGateway.completeDetailed(messages("corrupt-" + System.nanoTime()), false);

        assertFalse(result.success());
        assertEquals(service.id(), result.source().serviceId());
        assertEquals("CRYPTO_UNAVAILABLE", result.errorType());
        assertEquals(sentBefore, REQUEST_COUNT.get(), "损坏密文不得回退旧环境或其他服务");
    }

    @Test
    void currentEditFailureKeepsOldConfigAndSuccessIsAtomic() {
        AiServiceAdminDtos.ServiceVo service = create("当前服务", "model-current", "sk-current");
        assertTrue(testService.test(service.id(), service.version(), 1L).passed());
        int runtimeVersion = catalogService.runtimeView().runtimeVersion();
        updateService.activate(service.id(), service.version(), runtimeVersion, 1L);
        AiServiceAdminDtos.RuntimeVo activeRuntime = catalogService.runtimeView();

        AiServiceAdminDtos.UpdateRequest failing = update(service, "model-fail", "sk-current-new");
        failing.setRuntimeVersion(activeRuntime.runtimeVersion());
        AiServiceAdminDtos.UpdateResponse failed = updateService.update(service.id(), failing, 1L);

        assertFalse(failed.applied());
        assertFalse(failed.testReport().passed());
        assertEquals(1, failed.service().version());
        assertEquals("model-current", rawModel(service.id()), "候选测试失败不得写入线上记录");
        assertEquals(AiRuntimeConfig.MODE_MANAGED, catalogService.runtimeView().mode());
        assertEquals(service.id(), catalogService.runtimeView().currentServiceId());
        assertEquals("PASSED", jdbcTemplate.queryForObject(
                "SELECT last_test_status FROM ai_service_config WHERE id=?", String.class, service.id()));

        AiServiceAdminDtos.UpdateRequest succeeding = update(service, "model-current-v2", "sk-current-v2");
        succeeding.setRuntimeVersion(catalogService.runtimeView().runtimeVersion());
        AiServiceAdminDtos.UpdateResponse applied = updateService.update(service.id(), succeeding, 1L);

        assertTrue(applied.applied());
        assertTrue(applied.testReport().passed());
        assertEquals(2, applied.service().version());
        assertEquals(2, applied.service().lastTestVersion());
        assertEquals("model-current-v2", rawModel(service.id()));
        assertEquals("sk-current-v2", headerKey(LAST_AUTH.get()));
        assertTrue(catalogService.runtimeView().runtimeVersion() > activeRuntime.runtimeVersion());

        AiCompletionResult answer = aiGateway.completeDetailed(messages("after-current-edit"), false);
        assertTrue(answer.success());
        assertEquals("answer:model-current-v2", answer.content());
        assertEquals(2, answer.source().configVersion());
    }

    @Test
    void backupKeyBlankKeepsOldValueAndStaleVersionsAreRejected() {
        AiServiceAdminDtos.ServiceVo service = create("备选", "model-backup", "sk-backup-old");
        String originalCipher = rawCipher(service.id());
        assertTrue(testService.test(service.id(), service.version(), 1L).passed());

        AiServiceAdminDtos.UpdateRequest keepKey = update(service, "model-backup", null);
        AiServiceAdminDtos.ServiceVo edited = catalogService.update(service.id(), keepKey, 1L);
        assertEquals(originalCipher, rawCipher(service.id()), "编辑省略 apiKey 必须保留原密文");
        assertEquals(2, edited.version());
        assertEquals("STALE", edited.testState());

        AiServiceAdminDtos.UpdateRequest explicitBlank = update(edited, "model-backup", "");
        AiAdminException blankError = assertThrows(AiAdminException.class,
                () -> catalogService.update(service.id(), explicitBlank, 1L));
        assertEquals(400, blankError.getCode());

        AiServiceAdminDtos.UpdateRequest stale = update(edited, "model-backup", null);
        stale.setVersion(1);
        AiAdminException staleError = assertThrows(AiAdminException.class,
                () -> catalogService.update(service.id(), stale, 1L));
        assertEquals(409, staleError.getCode());
        assertEquals(originalCipher, rawCipher(service.id()));
    }

    @Test
    void offModeDoesNotSendRequestsAndCurrentCannotBeDisabledOrDeleted() throws Exception {
        AiServiceAdminDtos.ServiceVo service = create("当前", "model-off", "sk-off");
        assertTrue(testService.test(service.id(), service.version(), 1L).passed());
        int runtimeVersion = catalogService.runtimeView().runtimeVersion();
        updateService.activate(service.id(), service.version(), runtimeVersion, 1L);

        AiAdminException disable = assertThrows(AiAdminException.class,
                () -> catalogService.changeEnabled(service.id(), service.version(), false, 1L));
        assertEquals(409, disable.getCode());
        AiAdminException delete = assertThrows(AiAdminException.class,
                () -> catalogService.delete(service.id(), service.version(), 1L));
        assertEquals(409, delete.getCode());

        int sentBeforeOff = REQUEST_COUNT.get();
        catalogService.updateRuntime("OFF", catalogService.runtimeView().runtimeVersion(), 1L);
        AiCompletionResult off = aiGateway.completeDetailed(messages("off-must-not-call"), false);
        assertFalse(off.success());
        assertEquals("OFF", off.source().runtimeMode());
        assertEquals(sentBeforeOff, REQUEST_COUNT.get(), "OFF 模式不得产生任何上游请求");

        catalogService.updateRuntime("LEGACY", catalogService.runtimeView().runtimeVersion(), 1L);
        AiCompletionResult legacy = aiGateway.completeDetailed(messages("legacy-no-key"), false);
        assertFalse(legacy.success());
        assertEquals("LEGACY", legacy.source().runtimeMode());
        assertEquals(sentBeforeOff, REQUEST_COUNT.get(), "旧环境无 Key 时应在发请求前降级");
    }

    @Test
    void staleTestVersionCannotBeActivatedAndConcurrentSwitchHasSingleWinner() throws Exception {
        AiServiceAdminDtos.ServiceVo stale = create("过期候选", "model-stale", "sk-stale");
        assertTrue(testService.test(stale.id(), stale.version(), 1L).passed());
        AiServiceAdminDtos.UpdateRequest edit = update(stale, "model-stale-v2", null);
        AiServiceAdminDtos.ServiceVo edited = catalogService.update(stale.id(), edit, 1L);
        assertEquals("STALE", edited.testState());
        AiAdminException cannotActivate = assertThrows(AiAdminException.class,
                () -> updateService.activate(edited.id(), edited.version(),
                        catalogService.runtimeView().runtimeVersion(), 1L));
        assertEquals(409, cannotActivate.getCode());

        AiServiceAdminDtos.ServiceVo first = create("并发 A", "model-concurrent-a", "sk-ca");
        AiServiceAdminDtos.ServiceVo second = create("并发 B", "model-concurrent-b", "sk-cb");
        assertTrue(testService.test(first.id(), first.version(), 1L).passed());
        assertTrue(testService.test(second.id(), second.version(), 1L).passed());
        int runtimeVersion = catalogService.runtimeView().runtimeVersion();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<String> left = pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    updateService.activate(first.id(), first.version(), runtimeVersion, 1L);
                    return "OK";
                } catch (AiAdminException e) {
                    return String.valueOf(e.getCode());
                }
            });
            Future<String> right = pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    updateService.activate(second.id(), second.version(), runtimeVersion, 1L);
                    return "OK";
                } catch (AiAdminException e) {
                    return String.valueOf(e.getCode());
                }
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            List<String> outcomes = List.of(left.get(5, TimeUnit.SECONDS), right.get(5, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("OK"::equals).count(), "同一运行时版本只允许一个切换成功");
            assertEquals(1, outcomes.stream().filter("409"::equals).count());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(runtimeVersion + 1, catalogService.runtimeView().runtimeVersion());
    }

    @Test
    void runningRequestKeepsOriginalServiceWhileSwitchAffectsNextRequest() throws Exception {
        AiServiceAdminDtos.ServiceVo slow = create("运行中服务", "slow-model", "sk-slow");
        assertTrue(testService.test(slow.id(), slow.version(), 1L).passed());
        updateService.activate(slow.id(), slow.version(), catalogService.runtimeView().runtimeVersion(), 1L);

        AiServiceAdminDtos.ServiceVo next = create("下一服务", "model-next", "sk-next");
        assertTrue(testService.test(next.id(), next.version(), 1L).passed());

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SLOW_ENTERED.set(entered);
        SLOW_RELEASE.set(release);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AiCompletionResult> running = pool.submit(
                    () -> aiGateway.completeDetailed(messages("slow-running-" + System.nanoTime()), false));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "慢请求应已进入上游");

            updateService.activate(next.id(), next.version(),
                    catalogService.runtimeView().runtimeVersion(), 1L);
            release.countDown();

            AiCompletionResult result = running.get(10, TimeUnit.SECONDS);
            assertTrue(result.success());
            assertEquals(slow.id(), result.source().serviceId(), "运行中请求必须保持开始时取得的服务快照");
            assertEquals("answer:slow-model", result.content());

            AiCompletionResult afterSwitch = aiGateway.completeDetailed(
                    messages("after-switch-" + System.nanoTime()), false);
            assertEquals(next.id(), afterSwitch.source().serviceId());
            assertEquals("answer:model-next", afterSwitch.content());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanReadServicesEndpoint() throws Exception {
        mockMvc.perform(get("/admin/ai/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser(roles = "TEACHER")
    void teacherCannotReadServicesEndpoint() throws Exception {
        mockMvc.perform(get("/admin/ai/services"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @WithMockUser(roles = "USER")
    void studentCannotReadServicesEndpoint() throws Exception {
        mockMvc.perform(get("/admin/ai/services"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    private AiServiceAdminDtos.ServiceVo create(String name, String model, String key) {
        AiServiceAdminDtos.CreateRequest request = new AiServiceAdminDtos.CreateRequest();
        request.setName(name);
        request.setProtocol("CHAT_COMPLETIONS");
        request.setApiUrl(UPSTREAM_URL);
        request.setModel(model);
        request.setApiKey(key);
        request.setJsonMode(true);
        request.setStreamIncludeUsage(true);
        request.setMaxTokens(1024);
        return catalogService.create(request, 1L);
    }

    private static AiServiceAdminDtos.UpdateRequest update(AiServiceAdminDtos.ServiceVo service,
                                                           String model, String apiKey) {
        AiServiceAdminDtos.UpdateRequest request = new AiServiceAdminDtos.UpdateRequest();
        request.setName(service.name());
        request.setProtocol(service.protocol());
        request.setApiUrl(service.apiUrl());
        request.setModel(model);
        request.setRemark(service.remark());
        request.setJsonMode(service.jsonMode());
        request.setStreamIncludeUsage(service.streamIncludeUsage());
        request.setMaxTokens(service.maxTokens());
        request.setVersion(service.version());
        if (apiKey != null) {
            request.setApiKey(apiKey);
        }
        return request;
    }

    private String rawCipher(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT api_key_encrypted FROM ai_service_config WHERE id=?", String.class, id);
    }

    private String rawModel(Long id) {
        return jdbcTemplate.queryForObject("SELECT model FROM ai_service_config WHERE id=?", String.class, id);
    }

    private int aiServiceCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_service_config WHERE is_deleted=0", Integer.class);
        return count == null ? 0 : count;
    }

    private static String headerKey(String authorization) {
        assertNotNull(authorization);
        return authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
    }

    private static cn.hutool.json.JSONArray messages(String userText) {
        cn.hutool.json.JSONArray messages = new cn.hutool.json.JSONArray();
        JSONObject system = new JSONObject();
        system.set("role", "system");
        system.set("content", "integration-probe");
        messages.add(system);
        JSONObject user = new JSONObject();
        user.set("role", "user");
        user.set("content", userText);
        messages.add(user);
        return messages;
    }

    private static void handleUpstream(HttpExchange exchange) throws IOException {
        REQUEST_COUNT.incrementAndGet();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        LAST_AUTH.set(authorization);
        String requestText = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JSONObject request = new JSONObject(requestText);
        String model = request.getStr("model", "unknown");
        if (authorization == null || authorization.isBlank() || model.contains("fail")) {
            respond(exchange, 401, "{\"error\":\"upstream auth failed: secret-detail\"}");
            return;
        }
        if (model.startsWith("slow-") && SLOW_ENTERED.get() != null) {
            SLOW_ENTERED.get().countDown();
            try {
                SLOW_RELEASE.get().await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        boolean stream = request.getBool("stream", false);
        boolean json = request.containsKey("response_format");
        if (stream) {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String content = "stream:" + model;
            String payload = "data: {\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":2,\"total_tokens\":4}}\n"
                    + "data: [DONE]\n";
            exchange.getResponseBody().write(payload.getBytes(StandardCharsets.UTF_8));
            exchange.close();
            return;
        }
        String content = json ? "{\"ok\":true,\"provider\":\"" + model + "\"}" : "answer:" + model;
        String response = "{\"choices\":[{\"message\":{\"content\":" + jsonEscape(content) + "}}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";
        respond(exchange, 200, response);
    }

    private static String jsonEscape(String value) throws IOException {
        return new ObjectMapper().writeValueAsString(value);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
