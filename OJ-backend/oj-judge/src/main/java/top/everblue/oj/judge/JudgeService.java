package top.everblue.oj.judge;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import top.everblue.oj.api.judge.JudgeGateway;
import top.everblue.oj.judge.infrastructure.Judge0EndpointPool;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;

/**
 * Judge0 判题网关实现，负责把一次判题请求发出去、把 Judge0 的响应翻译成本项目的状态与资源字段。
 * <p>
 * 对外只暴露 {@link JudgeGateway} 这一个判题方法，因此其他模块（提交、作业、比赛复用代码）
 * 不依赖 Judge0 的具体协议，换判题引擎时改动范围被限制在本文件内。
 * </p>
 * <p>
 * 三个必须记住的设计约束：
 * </p>
 * <ol>
 *   <li><strong>全链路 base64 传输。</strong> Judge0 在 {@code base64_encoded=false} 下无法处理非 ASCII
 *       （学生代码里的中文注释就会触发），所以请求端编码、响应端统一用 {@code decodeB64} 解码。
 *       解码失败时原样返回而不是抛异常，是为了兼容少数返回明文的 Judge0 部署。</li>
 *   <li><strong>端点池 + 熔断 + 在途信号量。</strong> 背压必须挡在应用侧：并发用例数超过 Judge0 槽位时，
 *       若放任 wait=true 请求进入 Judge0 队列，会触发「队列超时 → 重试 → 更长的队列」的雪崩。
 *       单端点配置时该机制与旧版行为一致。</li>
 *   <li><strong>语言 ID 可配置。</strong> 不同 Judge0 部署的语言编号不同，写死在代码里等于绑定某个版本，
 *       所以默认值只作为 Judge0 CE v1.13.0 的兜底，实际取值以 {@code judge0.language-ids} 配置为准。</li>
 * </ol>
 * <p>
 * {@code enable_per_process_and_thread_*_limit} 是为 WSL2 / Docker Desktop(cgroup v2) 环境开的：
 * 开启后 Judge0 走 rlimit 而非 cgroup v1，否则在这些环境下一提交就 Internal Error。
 * 内存限制模式可配置：Apple Silicon 的 Rosetta 在 per-process 地址空间限制下可能无法启动。
 * 切换为聚合内存限制前，应先在目标 Judge0 环境实测。
 * </p>
 */
@Slf4j
@Service
public class JudgeService implements JudgeGateway {

    /** Judge0 端点池：轮询 + 熔断 + 请求内故障转移（单端点配置时行为与旧版一致） */
    private final Judge0EndpointPool endpointPool;

    /**
     * Judge0 HTTP 客户端（高并发方案 A5）。
     * <p>
     * 由 Hutool HttpRequest（每次新建 TCP 连接）换为 RestClient + JDK HttpClient：
     * JDK HttpClient 内置连接复用（Keep-Alive），判题并发化后不再被握手开销拖慢。
     * 连接超时 10s、读取超时 20s（与原行为一致）。
     * </p>
     */
    private final RestClient restClient;

    /**
     * Judge0 语言 ID 映射，可通过 {@code judge0.language-ids} 配置覆盖。
     * 默认值对应 Judge0 CE v1.13.0。
     */
    @Value("#{${judge0.language-ids:{java:62, c:50, cpp:54, python:71}}}")
    private Map<String, Integer> languageIds;

    @Value("${judge0.per-process-memory-limit:true}")
    private boolean perProcessMemoryLimit = true;

    public JudgeService(Judge0EndpointPool endpointPool) {
        this.endpointPool = endpointPool;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 调用判题机
     * @param timeLimitSec 秒 (例如 1.0)
     * @param memoryLimitKb KB (例如 262144)
     */
    @Override
    public Map<String, Object> judge(String code, String language, String input, String expectedOutput,
                                     Double timeLimitSec, Integer memoryLimitKb) {
        // 1. 构造请求体
        Map<String, Object> body = new HashMap<>();
        // Phase 0.1：Judge0 在 base64_encoded=false 时无法处理非 ASCII（如中文注释），
        // 统一改为 base64 传输，响应字段在 doJudge 内解码。
        body.put("source_code", Base64.getEncoder().encodeToString(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        body.put("language_id", getLangId(language));
        body.put("stdin", Base64.getEncoder().encodeToString(
                (input == null ? "" : input).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (expectedOutput != null) {
            body.put("expected_output", Base64.getEncoder().encodeToString(
                    expectedOutput.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        // 资源限制
        if (timeLimitSec != null) body.put("cpu_time_limit", timeLimitSec);
        if (memoryLimitKb != null) body.put("memory_limit", memoryLimitKb);
        // WSL2/Docker Desktop(cgroup v2)环境适配：启用 per-process 限制后，
        // Judge0 worker 的 isolate 走 rlimit 模式(不依赖 cgroup v1)，避免 Internal Error。
        body.put("enable_per_process_and_thread_time_limit", true);
        body.put("enable_per_process_and_thread_memory_limit", perProcessMemoryLimit);

        String requestJson = JSONUtil.toJsonStr(body);

        // 2. 按端点池顺序尝试：失败熔断该端点并故障转移到下一个
        //    发出请求前先申请在途额度（风险 1 预案）：并发用例数 > Judge0 槽位时，
        //    把背压挡在应用侧（信号量排队），避免 wait=true 请求在 Judge0 队列超时→重试→雪崩。
        //    注意：信号量等待是"排队"而非"请求"，等待上限需覆盖队列深度
        //    （80 用例任务抢 4 额度 ≈ 单用例 2s × 20 队首 ≈ 40s），60s 是安全上界；
        //    若 Judge0 彻底不可用，首个请求会快速失败并释放额度，不会长阻塞。
        List<String> candidates = endpointPool.candidates();
        RuntimeException lastError = null;
        for (String endpoint : candidates) {
            if (!endpointPool.tryAcquire(INFLIGHT_WAIT_MS)) {
                lastError = new RuntimeException("判题机在途请求已达上限（judge0.max-inflight），等待超时");
                log.warn("【Judge0】在途请求配额等待超时，跳过 endpoint={}, maxInflight 需对齐 worker 槽位数", endpoint);
                continue;
            }
            try {
                Map<String, Object> result = doJudge(endpoint, requestJson);
                endpointPool.onSuccess(endpoint);
                return result;
            } catch (Exception e) {
                endpointPool.onFailure(endpoint);
                lastError = new RuntimeException("判题机调用失败[" + endpoint + "]: " + e.getMessage(), e);
                log.warn("【Judge0】端点调用失败，尝试下一个 endpoint={}, err={}", endpoint, e.getMessage());
            } finally {
                endpointPool.release();
            }
        }
        throw lastError != null ? lastError : new RuntimeException("判题机调用失败: 无可用 Judge0 端点");
    }

    private static final long READ_TIMEOUT_MS = 20_000L;

    /** 信号量排队等待上限（见 judge() 中注释：队列深度 20 × 单用例 2s ≈ 40s，取 60s 安全上界） */
    private static final long INFLIGHT_WAIT_MS = 60_000L;

    /** 对单个 Judge0 端点发起一次同步判题请求（wait=true，连接复用） */
    private Map<String, Object> doJudge(String endpoint, String requestJson) {
        String resStr;
        try {
            resStr = restClient.post()
                    .uri(endpoint + "/submissions?base64_encoded=true&wait=true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }

        JSONObject json = JSONUtil.parseObj(resStr);
        Map<String, Object> result = new HashMap<>();

        // 获取 Judge0 的原始状态 ID
        if (json.containsKey("status")) {
            result.put("status", json.getJSONObject("status").getInt("id"));
        } else {
            result.put("status", 0); // 未知错误
        }

        // 获取资源消耗
        result.put("time", json.getStr("time")); // 原始数据是秒，如 "0.012"
        result.put("memory", json.getStr("memory")); // 原始数据是KB，如 "1280"
        result.put("compile_output", decodeB64(json.getStr("compile_output"))); // 编译错误信息
        result.put("message", decodeB64(json.getStr("message"))); // 运行时报错信息
        result.put("stderr", decodeB64(json.getStr("stderr"))); // 标准错误输出

        result.put("stdout", decodeB64(json.getStr("stdout")));

        log.debug("Judge0 result: compile={}, stderr={}, message={}",
                result.get("compile_output"), result.get("stderr"), result.get("message"));

        return result;
    }

    private int getLangId(String lang) {
        String normalized = normalizeLanguage(lang);
        return languageIds.getOrDefault(normalized, languageIds.getOrDefault("python", 71));
    }

    private String decodeB64(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            // Judge0 的 base64 响应可能带换行，getMimeDecoder 会忽略空白字符
            return new String(Base64.getMimeDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value; // 非 base64（如 Judge0 直接返回明文）时原样返回
        }
    }

    private String normalizeLanguage(String lang) {
        if (lang == null) return "python";

        String normalized = lang.trim().toLowerCase();
        switch (normalized) {
            case "java":
            case "java8":
            case "java 8":
                return "java";
            case "c":
                return "c";
            case "cpp":
            case "c++":
            case "c++14":
            case "c++17":
                return "cpp";
            case "python":
            case "python3":
            case "py":
                return "python";
            default:
                return "python";
        }
    }
}
