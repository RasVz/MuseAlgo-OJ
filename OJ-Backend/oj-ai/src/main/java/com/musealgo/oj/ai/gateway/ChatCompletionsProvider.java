package com.musealgo.oj.ai.gateway;

import cn.hutool.json.JSONArray;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.musealgo.oj.ai.dto.AiCompletionResult;
import com.musealgo.oj.ai.dto.AiStreamResult;
import com.musealgo.oj.ai.runtime.AiServiceSnapshot;

import java.util.function.Consumer;

/** Chat Completions 兼容协议适配器；不再绑定具体供应商名称。 */
@Order(1)
@Component
@RequiredArgsConstructor
public class ChatCompletionsProvider implements AiProvider {

    private final ChatCompletionsClient chatClient;

    @Override
    public String name() {
        return "chat-completions";
    }

    @Override
    public String protocol() {
        return AiServiceSnapshot.PROTOCOL_CHAT_COMPLETIONS;
    }

    @Override
    public AiCompletionResult complete(AiServiceSnapshot snapshot, JSONArray messages, boolean jsonMode) {
        return chatClient.complete(snapshot, messages, jsonMode);
    }

    @Override
    public AiStreamResult stream(AiServiceSnapshot snapshot, JSONArray messages, Consumer<String> onChunk) {
        return chatClient.stream(snapshot, messages, onChunk);
    }
}
