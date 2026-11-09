package com.musealgo.oj.ai.gateway;

import cn.hutool.json.JSONArray;
import com.musealgo.oj.ai.dto.AiCompletionResult;
import com.musealgo.oj.ai.dto.AiStreamResult;
import com.musealgo.oj.ai.runtime.AiServiceSnapshot;

import java.util.function.Consumer;

/**
 * 模型协议 SPI。
 *
 * <p>供应商不再在启动时绑定 URL/Key：每次调用接收完整配置快照，统一返回带来源的中性结果。
 * 失败不得抛到业务层；连接断开等调用方控制异常可以继续向上传播。</p>
 */
public interface AiProvider {

    String name();

    String protocol();

    AiCompletionResult complete(AiServiceSnapshot snapshot, JSONArray messages, boolean jsonMode);

    AiStreamResult stream(AiServiceSnapshot snapshot, JSONArray messages, Consumer<String> onChunk);
    default AiStreamResult stream(AiServiceSnapshot snapshot, JSONArray messages, JSONArray tools, Consumer<String> onChunk) {
        return stream(snapshot, messages, onChunk);
    }
}
