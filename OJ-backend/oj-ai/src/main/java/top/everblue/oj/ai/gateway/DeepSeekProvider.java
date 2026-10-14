package top.everblue.oj.ai.gateway;

import cn.hutool.json.JSONArray;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import top.everblue.oj.ai.DeepSeekClient;

import java.util.function.Consumer;

/**
 * DeepSeek 供应商适配器：把 {@link DeepSeekClient} 传输层暴露为 {@link AiProvider}。
 */
@Order(1)
@Component
@RequiredArgsConstructor
public class DeepSeekProvider implements AiProvider {

    private final DeepSeekClient deepSeekClient;

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public String complete(JSONArray messages, boolean jsonMode) {
        return jsonMode ? deepSeekClient.chatJson(messages) : deepSeekClient.chat(messages);
    }

    @Override
    public DeepSeekClient.StreamCallResult stream(JSONArray messages, Consumer<String> onChunk) {
        return deepSeekClient.streamDetailed(messages, onChunk);
    }
}
