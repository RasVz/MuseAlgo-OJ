package top.everblue.oj.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.everblue.oj.ai.config.DynamicAiConfig;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * DeepSeek 传输层：只负责 HTTP 调用与流式解析。
 * <p>
 * 不关心业务（历史、检索、降级文案）。所有失败统一返回 {@code null}，
 * 由上层决定降级策略，避免把"调用细节"泄漏到业务代码里。
 */
@Slf4j
@Component
public class DeepSeekClient {

    // 大模型 API Key（禁止默认值：密钥不得进代码；为空时走降级，见下方空值检查）
    @Value("${ai.api-key:}")
    private String apiKey;

    // 大模型的接口地址
    @Value("${ai.api-url:https://api.deepseek.com/v1/chat/completions}")
    private String apiUrl;

    private final DynamicAiConfig config;

    private final OkHttpClient client;

    public DeepSeekClient(DynamicAiConfig config) {
        this.config = config;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                // 长题面翻译生成量较大，10 秒读超时容易误杀正常请求
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    /** 普通对话：返回回复文本；失败返回 null */
    public String chat(JSONArray messages) {
        return callModel(messages, false);
    }

    /** 结构化对话：返回 JSON 文本；失败返回 null（调用方自行处理，不伪装成 JSON） */
    public String chatJson(JSONArray messages) {
        return callModel(messages, config.jsonMode());
    }

    /**
     * 流式对话：模型边生成边通过 onChunk 回调增量文本，
     * 返回拼接后的完整回答；失败返回 null。
     */
    public String stream(JSONArray messages, Consumer<String> onChunk) {
        StreamCallResult result = streamDetailed(messages, onChunk);
        return result == null ? null : result.content();
    }

    /**
     * 带用量统计的流式对话。
     * <p>
     * 通过 {@code stream_options.include_usage=true} 让 API 在末尾追加 usage 块，
     * 供配额入账与成本核算使用；供应商不支持该参数时 usage 为 null，调用方退化为估算。
     * </p>
     *
     * @return 完整回答 + token 用量；失败返回 null
     */
    public StreamCallResult streamDetailed(JSONArray messages, Consumer<String> onChunk) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("AI API Key 未配置（ai.api-key 为空），请设置 DEEPSEEK_API_KEY 环境变量或检查配置");
            return null;
        }

        JSONObject requestBody = new JSONObject();
        requestBody.set("model", config.model());
        requestBody.set("messages", messages);
        requestBody.set("stream", true);
        requestBody.set("temperature", 0.5);
        // 让流末尾携带 usage（DeepSeek 支持；不支持的供应商会忽略该字段）
        JSONObject streamOptions = new JSONObject();
        streamOptions.set("include_usage", true);
        requestBody.set("stream_options", streamOptions);

        RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        StringBuilder full = new StringBuilder();
        TokenUsage usage = null;
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                log.error("AI 流式请求失败: {}", errorBody);
                return null;
            }

            // SSE 协议：一行 data: {json}，流结束时收到 data: [DONE]
            BufferedSource source = response.body().source();
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                TokenUsage chunkUsage = extractUsage(data);
                if (chunkUsage != null) {
                    usage = chunkUsage;
                    continue; // usage 块 choices 为空，无需再走内容解析
                }
                String content = extractDeltaContent(data);
                if (content != null && !content.isEmpty()) {
                    full.append(content);
                    onChunk.accept(content);
                }
            }
            return new StreamCallResult(full.toString(), usage);

        } catch (IOException e) {
            log.error("AI 流式网络异常", e);
            return null;
        } catch (Exception e) {
            log.error("AI 流式响应解析异常", e);
            return null;
        }
    }

    /** 一次流式调用的结果：完整文本 + API 返回的 token 用量（可能为 null） */
    public record StreamCallResult(String content, TokenUsage usage) {
    }

    /** API 返回的 token 用量 */
    public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
    }

    /** 从流式 chunk 中提取 usage（include_usage 时末尾块 choices 为空、仅含 usage） */
    private static TokenUsage extractUsage(String data) {
        try {
            JSONObject chunk = new JSONObject(data);
            if (!chunk.containsKey("usage") || chunk.get("usage") == null) {
                return null;
            }
            JSONObject usage = chunk.getJSONObject("usage");
            return new TokenUsage(
                    usage.getInt("prompt_tokens", 0),
                    usage.getInt("completion_tokens", 0),
                    usage.getInt("total_tokens", 0));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 共用的非流式调用：拼装 OpenAI 兼容的 chat/completions 请求并解析首条回复。
     */
    private String callModel(JSONArray messages, boolean jsonResponse) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("AI API Key 未配置（ai.api-key 为空），请设置 DEEPSEEK_API_KEY 环境变量或检查配置");
            return null;
        }

        JSONObject requestBody = new JSONObject();
        requestBody.set("model", config.model());
        requestBody.set("messages", messages);
        if (jsonResponse) {
            // 强制模型输出合法 JSON，避免 Markdown 围栏/解释性文字导致解析失败；
            // DeepSeek 要求提示词中包含 "json" 字样并给出格式说明（由调用方保证）。
            JSONObject responseFormat = new JSONObject();
            responseFormat.set("type", "json_object");
            requestBody.set("response_format", responseFormat);
            // 结构化输出对格式稳定性要求更高，进一步降低随机性
            requestBody.set("temperature", 0.2);
            // 防止长题面翻译结果被 max_tokens 截断成非法 JSON
            requestBody.set("max_tokens", config.maxTokens());
        } else {
            // 控制模型输出的随机性，稍微低一点保证解答的严谨性
            requestBody.set("temperature", 0.5);
        }

        RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            // 注意：ResponseBody 是一次性流，只能消费一次。
            // 先取出字符串，再根据状态码决定如何使用，避免二次读取抛 IllegalStateException。
            String responseString = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("AI 请求失败: {}", responseString);
                return null;
            }

            JSONObject parsedResponse = new JSONObject(responseString);
            JSONArray choices = parsedResponse.getJSONArray("choices");
            JSONObject firstChoice = choices.getJSONObject(0);
            return firstChoice.getJSONObject("message").getStr("content");

        } catch (IOException e) {
            log.error("AI 网络请求异常", e);
            return null;
        } catch (Exception e) {
            log.error("AI 响应解析异常", e);
            return null;
        }
    }

    /** 从 OpenAI 兼容的流式 chunk 里取出文本增量（首块只带 role，content 可能为 null） */
    private static String extractDeltaContent(String data) {
        try {
            JSONObject chunk = new JSONObject(data);
            return chunk.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("delta")
                    .getStr("content");
        } catch (Exception e) {
            return null;
        }
    }
}
