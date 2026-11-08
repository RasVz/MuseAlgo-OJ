package com.musealgo.oj.ai.gateway;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.musealgo.oj.ai.dto.AiCallSource;
import com.musealgo.oj.ai.dto.AiCompletionResult;
import com.musealgo.oj.ai.dto.AiStreamResult;
import com.musealgo.oj.ai.dto.AiTokenUsage;
import com.musealgo.oj.ai.network.AiEndpointGuard;
import com.musealgo.oj.ai.runtime.AiServiceSnapshot;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Chat Completions 兼容传输层。
 *
 * <p>只发送配置快照中的地址、模型和密钥；禁止重定向，连接时重新解析并校验 DNS。
 * 上游错误只映射为稳定错误类型，不把响应正文写入日志或返回给浏览器。</p>
 */
@Slf4j
@Component
public class ChatCompletionsClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int DEFAULT_CONNECT_SECONDS = 10;
    private static final int DEFAULT_READ_SECONDS = 120;
    private static final int DEFAULT_CALL_SECONDS = 150;
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 1024L * 1024L;
    private static final long DEFAULT_MAX_STREAM_BYTES = 8L * 1024L * 1024L;
    private static final int DEFAULT_MAX_LINE_BYTES = 64 * 1024;

    private final AiEndpointGuard endpointGuard;
    private final OkHttpClient client;
    private final long maxResponseBytes;
    private final long maxStreamBytes;
    private final int maxLineBytes;

    @Autowired
    public ChatCompletionsClient(AiEndpointGuard endpointGuard) {
        this(endpointGuard, DEFAULT_CONNECT_SECONDS, DEFAULT_READ_SECONDS, DEFAULT_CALL_SECONDS,
                DEFAULT_MAX_RESPONSE_BYTES, DEFAULT_MAX_STREAM_BYTES, DEFAULT_MAX_LINE_BYTES);
    }

    ChatCompletionsClient(AiEndpointGuard endpointGuard, int connectSeconds, int readSeconds, int callSeconds,
                          long maxResponseBytes, long maxStreamBytes, int maxLineBytes) {
        this.endpointGuard = endpointGuard;
        this.maxResponseBytes = maxResponseBytes;
        this.maxStreamBytes = maxStreamBytes;
        this.maxLineBytes = maxLineBytes;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(connectSeconds, TimeUnit.SECONDS)
                .readTimeout(readSeconds, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(callSeconds, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .dns(endpointGuard.guardedDns())
                .build();
    }

    /** 连接测试使用更短超时，避免三个探测串行拖住管理请求；限制值与防护不变。 */
    public ChatCompletionsClient withTimeouts(int connectSeconds, int readSeconds, int callSeconds) {
        return new ChatCompletionsClient(endpointGuard, connectSeconds, readSeconds, callSeconds,
                maxResponseBytes, maxStreamBytes, maxLineBytes);
    }

    public AiCompletionResult complete(AiServiceSnapshot snapshot, JSONArray messages, boolean jsonMode) {
        AiCallSource source = snapshot.source();
        String unavailable = unavailableReason(snapshot);
        if (unavailable != null) {
            return AiCompletionResult.failure(source, unavailable, safeUnavailableMessage(snapshot));
        }
        URI endpoint;
        try {
            endpoint = resolveEndpoint(snapshot);
        } catch (IllegalArgumentException e) {
            return AiCompletionResult.failure(source, "NETWORK", "接口地址未通过安全检查");
        }

        boolean effectiveJson = jsonMode && snapshot.jsonMode();
        JSONObject requestBody = baseRequest(snapshot, messages, effectiveJson);
        Request request;
        try {
            request = new Request.Builder()
                    .url(endpoint.toString())
                    .addHeader("Authorization", "Bearer " + snapshot.apiKey())
                    .post(RequestBody.create(requestBody.toString(), JSON))
                    .build();
        } catch (IllegalArgumentException e) {
            return AiCompletionResult.failure(source, "INVALID_RESPONSE", "接口地址格式无效");
        }

        try (Response response = clientForEndpoint(endpoint).newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String type = classifyHttpStatus(response.code());
                log.warn("【AI上游】非成功响应 status={} type={} serviceId={} reason={}",
                        response.code(), type, snapshot.serviceId(), upstreamReason(response));
                return AiCompletionResult.failure(source, type, httpErrorMessage(type));
            }
            ResponseBody body = response.body();
            if (body == null) {
                return AiCompletionResult.failure(source, "INVALID_RESPONSE", "上游返回空响应");
            }
            long declared = body.contentLength();
            if (declared > maxResponseBytes) {
                return AiCompletionResult.failure(source, "RESPONSE_TOO_LARGE", "上游响应超过大小限制");
            }
            String raw = readBounded(body, maxResponseBytes);
            JSONObject parsed = new JSONObject(raw);
            String content = extractMessageContent(parsed);
            if (content == null || content.isBlank()) {
                return AiCompletionResult.failure(source, "INVALID_RESPONSE", "上游响应缺少有效内容");
            }
            return AiCompletionResult.success(content, extractUsage(parsed), source);
        } catch (ResponseTooLargeException e) {
            return AiCompletionResult.failure(source, "RESPONSE_TOO_LARGE", "上游响应超过大小限制");
        } catch (SocketTimeoutException e) {
            return AiCompletionResult.failure(source, "TIMEOUT", "上游请求超时");
        } catch (InterruptedIOException e) {
            return AiCompletionResult.failure(source, "TIMEOUT", "上游请求超时");
        } catch (IOException e) {
            log.warn("【AI上游】网络失败 serviceId={} exception={}", snapshot.serviceId(), e.getClass().getSimpleName());
            return AiCompletionResult.failure(source, "NETWORK", "无法连接上游服务");
        } catch (RuntimeException e) {
            log.warn("【AI上游】响应解析失败 serviceId={} exception={}", snapshot.serviceId(), e.getClass().getSimpleName());
            return AiCompletionResult.failure(source, "INVALID_RESPONSE", "上游响应格式无效");
        }
    }

    public AiStreamResult stream(AiServiceSnapshot snapshot, JSONArray messages, Consumer<String> onChunk) {
        AiCallSource source = snapshot.source();
        String unavailable = unavailableReason(snapshot);
        if (unavailable != null) {
            return AiStreamResult.failure(source, false, unavailable, safeUnavailableMessage(snapshot));
        }
        URI endpoint;
        try {
            endpoint = resolveEndpoint(snapshot);
        } catch (IllegalArgumentException e) {
            return AiStreamResult.failure(source, false, "NETWORK", "接口地址未通过安全检查");
        }

        JSONObject requestBody = baseRequest(snapshot, messages, false);
        requestBody.set("stream", true);
        if (snapshot.streamIncludeUsage()) {
            JSONObject streamOptions = new JSONObject();
            streamOptions.set("include_usage", true);
            requestBody.set("stream_options", streamOptions);
        }
        Request request;
        try {
            request = new Request.Builder()
                    .url(endpoint.toString())
                    .addHeader("Authorization", "Bearer " + snapshot.apiKey())
                    .post(RequestBody.create(requestBody.toString(), JSON))
                    .build();
        } catch (IllegalArgumentException e) {
            return AiStreamResult.failure(source, false, "INVALID_RESPONSE", "接口地址格式无效");
        }

        StringBuilder full = new StringBuilder();
        AiTokenUsage usage = null;
        boolean emitted = false;
        boolean finished = false;
        long totalBytes = 0;
        try (Response response = clientForEndpoint(endpoint).newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String type = classifyHttpStatus(response.code());
                log.warn("【AI上游】流式非成功响应 status={} type={} serviceId={} reason={}",
                        response.code(), type, snapshot.serviceId(), upstreamReason(response));
                return AiStreamResult.failure(source, false, type, httpErrorMessage(type));
            }
            ResponseBody body = response.body();
            if (body == null) {
                return AiStreamResult.failure(source, false, "INVALID_RESPONSE", "上游返回空响应");
            }
            BufferedSource input = body.source();
            while (true) {
                SseLine next = readStreamLine(input, maxStreamBytes - totalBytes);
                if (next == null) {
                    break;
                }
                String line = next.text();
                totalBytes += next.bytes();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    finished = true;
                    break;
                }
                if (data.isEmpty()) {
                    continue;
                }
                AiTokenUsage chunkUsage;
                String delta;
                try {
                    JSONObject chunk = new JSONObject(data);
                    if (chunk.containsKey("error")) {
                        return AiStreamResult.failure(source, emitted, "MODEL_UNAVAILABLE", "上游返回流式错误");
                    }
                    chunkUsage = extractUsage(chunk);
                    delta = extractDeltaContent(chunk);
                    JSONArray choices = chunk.getJSONArray("choices");
                    if (choices != null && !choices.isEmpty()) {
                        String reason = choices.getJSONObject(0).getStr("finish_reason");
                        finished |= reason != null && !reason.isBlank();
                    }
                } catch (RuntimeException e) {
                    return AiStreamResult.failure(source, emitted, "INVALID_RESPONSE", "上游流式响应格式无效");
                }
                if (chunkUsage != null) {
                    usage = chunkUsage;
                }
                if (delta != null && !delta.isEmpty()) {
                    full.append(delta);
                    emitted = true;
                    onChunk.accept(delta);
                }
            }
            if (full.isEmpty()) {
                return AiStreamResult.failure(source, false, "INVALID_RESPONSE", "上游流式响应缺少有效内容");
            }
            if (!finished) {
                return AiStreamResult.failure(source, emitted, "NETWORK", "上游流式连接未完整结束");
            }
            return AiStreamResult.success(full.toString(), usage, source, emitted);
        } catch (ResponseTooLargeException e) {
            return AiStreamResult.failure(source, emitted, "RESPONSE_TOO_LARGE", "上游流式响应超过大小限制");
        } catch (SocketTimeoutException e) {
            return AiStreamResult.failure(source, emitted, "TIMEOUT", "上游流式请求超时");
        } catch (InterruptedIOException e) {
            return AiStreamResult.failure(source, emitted, "TIMEOUT", "上游流式请求超时");
        } catch (IOException e) {
            log.warn("【AI上游】流式网络失败 serviceId={} exception={}",
                    snapshot.serviceId(), e.getClass().getSimpleName());
            return AiStreamResult.failure(source, emitted, "NETWORK", "上游流式连接中断");
        }
    }

    private OkHttpClient clientForEndpoint(URI endpoint) {
        // 普通公网调用复用同一 DNS 策略和连接池；私网例外必须绑定完整请求地址。
        if (!endpointGuard.isExactPrivateTarget(endpoint.toString())) {
            return client;
        }
        return client.newBuilder().dns(endpointGuard.guardedDns(endpoint)).build();
    }

    private SseLine readStreamLine(BufferedSource input, long remainingBytes) throws IOException {
        // 先限定搜索范围，避免 readUtf8Line 为永不换行的响应无限积累内存。
        long scanLimit = Math.min(maxLineBytes + 2L, remainingBytes + 1L);
        long newline = input.indexOf((byte) '\n', 0, scanLimit);
        if (newline < 0) {
            long buffered = input.getBuffer().size();
            if (buffered > maxLineBytes || buffered > remainingBytes) {
                throw new ResponseTooLargeException();
            }
            return buffered == 0 ? null : new SseLine(input.readUtf8(buffered), buffered);
        }
        long contentBytes = newline;
        if (newline > 0 && input.getBuffer().getByte(newline - 1) == '\r') {
            contentBytes--;
        }
        if (contentBytes > maxLineBytes || newline + 1 > remainingBytes) {
            throw new ResponseTooLargeException();
        }
        return new SseLine(input.readUtf8LineStrict(maxLineBytes), newline + 1);
    }

    private record SseLine(String text, long bytes) {
    }

    private URI resolveEndpoint(AiServiceSnapshot snapshot) {
        if (!"LEGACY".equals(snapshot.mode())) {
            return endpointGuard.validate(snapshot.endpoint().toString());
        }
        return snapshot.endpoint();
    }

    private static JSONObject baseRequest(AiServiceSnapshot snapshot, JSONArray messages, boolean jsonMode) {
        JSONObject body = new JSONObject();
        body.set("model", snapshot.model());
        body.set("messages", messages);
        body.set("temperature", jsonMode ? 0.2 : 0.5);
        if (jsonMode) {
            JSONObject responseFormat = new JSONObject();
            responseFormat.set("type", "json_object");
            body.set("response_format", responseFormat);
        }
        if (!"LEGACY".equals(snapshot.mode()) || jsonMode) {
            body.set("max_tokens", snapshot.maxTokens());
        }
        return body;
    }

    private static String unavailableReason(AiServiceSnapshot snapshot) {
        if (!snapshot.available()) {
            return snapshot.unavailableType() == null ? "MODEL_UNAVAILABLE" : snapshot.unavailableType();
        }
        if (!snapshot.supportsChatCompletions()) {
            return "INVALID_RESPONSE";
        }
        if (snapshot.apiKey() == null || snapshot.apiKey().isBlank()) {
            return "AUTH_FAILED";
        }
        if (snapshot.endpoint() == null) {
            return "INVALID_RESPONSE";
        }
        return null;
    }

    private static String safeUnavailableMessage(AiServiceSnapshot snapshot) {
        if (snapshot.unavailableMessage() != null) {
            return snapshot.unavailableMessage();
        }
        return "AI 服务当前不可用";
    }

    /**
     * 读取上游错误响应的原因，仅用于日志定位。
     * <p>
     * 上游拒绝请求时只回 status 会让排障变成猜测（模型名/参数/额度无法区分）；
     * 这里只取 {@code error.message} 或 {@code message}，截断到 200 字符，
     * 不回写业务响应，也不向用户侧透露。
     * </p>
     */
    private static String upstreamReason(Response response) {
        try {
            ResponseBody body = response.body();
            if (body == null) {
                return "";
            }
            String raw = body.string();
            if (raw == null || raw.isBlank()) {
                return "";
            }
            String message = raw;
            try {
                JSONObject parsed = new JSONObject(raw);
                JSONObject error = parsed.getJSONObject("error");
                String extracted = error != null ? error.getStr("message") : parsed.getStr("message");
                if (extracted != null && !extracted.isBlank()) {
                    message = extracted;
                }
            } catch (RuntimeException ignored) {
                // 非 JSON 响应按原文截断
            }
            message = message.replaceAll("\\s+", " ").trim();
            return message.length() > 200 ? message.substring(0, 200) : message;
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static String readBounded(ResponseBody body, long limit) throws IOException {
        try (BufferedSource source = body.source()) {
            Buffer buffer = new Buffer();
            long total = 0;
            long read;
            while ((read = source.read(buffer, 8192)) != -1) {
                total += read;
                if (total > limit) {
                    throw new ResponseTooLargeException();
                }
            }
            return buffer.readUtf8();
        }
    }

    private static String extractMessageContent(JSONObject response) {
        JSONArray choices = response.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JSONObject first = choices.getJSONObject(0);
        if (first == null) {
            return null;
        }
        JSONObject message = first.getJSONObject("message");
        return message == null ? null : message.getStr("content");
    }

    private static String extractDeltaContent(JSONObject chunk) {
        JSONArray choices = chunk.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JSONObject first = choices.getJSONObject(0);
        if (first == null) {
            return null;
        }
        JSONObject delta = first.getJSONObject("delta");
        return delta == null ? null : delta.getStr("content");
    }

    private static AiTokenUsage extractUsage(JSONObject object) {
        JSONObject usage = object.getJSONObject("usage");
        if (usage == null) {
            return null;
        }
        int prompt = usage.getInt("prompt_tokens", 0);
        int completion = usage.getInt("completion_tokens", 0);
        int total = usage.getInt("total_tokens", prompt + completion);
        return new AiTokenUsage(prompt, completion, total);
    }

    private static String classifyHttpStatus(int status) {
        if (status == 401 || status == 403) {
            return "AUTH_FAILED";
        }
        if (status == 429) {
            return "QUOTA_EXCEEDED";
        }
        if (status == 408 || status == 504) {
            return "TIMEOUT";
        }
        if (status >= 300 && status < 400) {
            return "REDIRECT_BLOCKED";
        }
        if (status >= 500 || status == 400 || status == 404 || status == 422) {
            return "MODEL_UNAVAILABLE";
        }
        return "INVALID_RESPONSE";
    }

    private static String httpErrorMessage(String type) {
        return switch (type) {
            case "AUTH_FAILED" -> "上游认证或权限失败";
            case "QUOTA_EXCEEDED" -> "上游额度不足或被限流";
            case "TIMEOUT" -> "上游请求超时";
            case "REDIRECT_BLOCKED" -> "上游返回重定向，已拒绝跟随";
            case "MODEL_UNAVAILABLE" -> "上游模型不可用或拒绝参数";
            default -> "上游返回无效响应";
        };
    }

    private static final class ResponseTooLargeException extends IOException {
        private ResponseTooLargeException() {
            super("response too large");
        }
    }
}
