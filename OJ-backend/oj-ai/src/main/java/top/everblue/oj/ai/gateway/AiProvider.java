package top.everblue.oj.ai.gateway;

import cn.hutool.json.JSONArray;
import top.everblue.oj.ai.DeepSeekClient;

import java.util.function.Consumer;

/**
 * 模型供应商 SPI（O5 AI 网关层）。
 * <p>
 * 每个供应商一个实现（DeepSeek 是第一家），由 {@link AiGateway} 按优先级注入：
 * 一个失败自动尝试下一个，业务层无感知。失败统一返回 {@code null}。
 * </p>
 */
public interface AiProvider {

    /** 供应商名（日志与指标用），如 "deepseek" */
    String name();

    /**
     * 非流式完成。
     *
     * @param jsonMode true=结构化 JSON 输出（chatJson），false=普通对话
     * @return 回复文本；失败返回 null
     */
    String complete(JSONArray messages, boolean jsonMode);

    /**
     * 流式完成：边生成边回调，返回完整文本 + token 用量。
     *
     * @return 内容与 usage；失败返回 null
     */
    DeepSeekClient.StreamCallResult stream(JSONArray messages, Consumer<String> onChunk);
}
