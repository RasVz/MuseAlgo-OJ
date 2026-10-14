package top.everblue.oj.ai.gateway;

import cn.hutool.json.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;
import top.everblue.oj.ai.DeepSeekClient;

import java.util.List;
import java.util.function.Consumer;

/**
 * AI 网关（O5）：供应商故障切换 + 响应缓存。
 * <p>
 * 业务层（AiService）只依赖本网关，不再直连具体供应商：
 * 新增供应商 = 新增一个 {@link AiProvider} bean（可用 {@code @Order} 指定优先级），
 * 第一个可用供应商成功即返回，全部失败返回 {@code null} 由调用方降级。
 * </p>
 */
@Slf4j
@Service
public class AiGateway {

    private final List<AiProvider> providers;
    private final AiResponseCache responseCache;

    public AiGateway(List<AiProvider> providers, AiResponseCache responseCache) {
        providers.sort(AnnotationAwareOrderComparator.INSTANCE);
        this.providers = providers;
        this.responseCache = responseCache;
    }

    /**
     * 非流式完成：命中缓存直接返回；否则按优先级尝试供应商。
     *
     * @param jsonMode true=结构化 JSON 输出（不缓存，保证时效性）
     * @return 回复文本；全部供应商失败返回 null
     */
    public String complete(JSONArray messages, boolean jsonMode) {
        if (!jsonMode) {
            String cached = responseCache.get(messages);
            if (cached != null) {
                return cached;
            }
        }
        for (AiProvider provider : providers) {
            String answer = provider.complete(messages, jsonMode);
            if (answer != null) {
                if (!jsonMode) {
                    responseCache.put(messages, answer);
                }
                return answer;
            }
            log.warn("【AI网关】供应商 {} 返回空，尝试下一个", provider.name());
        }
        log.error("【AI网关】所有供应商均失败");
        return null;
    }

    /**
     * 流式完成：按优先级尝试供应商。
     * <p>
     * 注意：流式已回推给客户端的 chunk 无法撤回，因此故障切换仅在
     * 「当前供应商在产生任何内容前就失败」时生效；中途断流由调用方按失败降级。
     * </p>
     *
     * @return 完整内容 + token 用量；全部失败返回 null
     */
    public DeepSeekClient.StreamCallResult stream(JSONArray messages, Consumer<String> onChunk) {
        for (AiProvider provider : providers) {
            DeepSeekClient.StreamCallResult result = provider.stream(messages, onChunk);
            if (result != null && result.content() != null) {
                return result;
            }
            log.warn("【AI网关】供应商 {} 流式失败，尝试下一个", provider.name());
        }
        log.error("【AI网关】所有供应商流式均失败");
        return null;
    }
}
