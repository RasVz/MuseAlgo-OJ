package com.musealgo.oj.ai.gateway;

import cn.hutool.json.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;
import com.musealgo.oj.ai.dto.AiCallSource;
import com.musealgo.oj.ai.dto.AiCompletionResult;
import com.musealgo.oj.ai.dto.AiStreamResult;
import com.musealgo.oj.ai.runtime.AiRuntimeConfigResolver;
import com.musealgo.oj.ai.runtime.AiServiceSnapshot;

import java.util.List;
import java.util.function.Consumer;

/**
 * AI 网关：每次调用先解析完整配置快照，再选择协议适配器。
 *
 * <p>MANAGED 只调用当前服务一次，失败不回退环境变量或其他服务；LEGACY 保留原有的
 * 多供应商顺序尝试。流式已经输出内容后不跨供应商重试。</p>
 */
@Slf4j
@Service
public class AiGateway {

    private final List<AiProvider> providers;
    private final AiResponseCache responseCache;
    private final AiRuntimeConfigResolver runtimeResolver;

    public AiGateway(List<AiProvider> providers,
                     AiResponseCache responseCache,
                     AiRuntimeConfigResolver runtimeResolver) {
        List<AiProvider> sorted = new java.util.ArrayList<>(providers);
        sorted.sort(AnnotationAwareOrderComparator.INSTANCE);
        this.providers = List.copyOf(sorted);
        this.responseCache = responseCache;
        this.runtimeResolver = runtimeResolver;
    }

    /** 兼容字符串调用方；需要来源时使用 {@link #completeDetailed}。 */
    public String complete(JSONArray messages, boolean jsonMode) {
        AiCompletionResult result = completeDetailed(messages, jsonMode);
        return result.success() ? result.content() : null;
    }

    public AiCompletionResult completeDetailed(JSONArray messages, boolean jsonMode) {
        AiServiceSnapshot snapshot;
        try {
            snapshot = runtimeResolver.resolveCurrent();
        } catch (RuntimeException e) {
            log.error("【AI网关】运行时配置读取失败 type={}", e.getClass().getSimpleName());
            return AiCompletionResult.failure(AiCallSource.unavailable(), "MODEL_UNAVAILABLE",
                    "AI 运行配置读取失败");
        }
        if (!snapshot.available()) {
            return unavailableCompletion(snapshot);
        }
        if (!jsonMode) {
            String cached = responseCache.get(snapshot.cacheScope(), messages);
            if (cached != null) {
                return AiCompletionResult.success(cached, null, snapshot.source());
            }
        }
        List<AiProvider> candidates = candidates(snapshot);
        if (candidates.isEmpty()) {
            return AiCompletionResult.failure(snapshot.source(), "INVALID_RESPONSE", "没有匹配的 AI 协议适配器");
        }

        AiCompletionResult last = null;
        for (AiProvider provider : candidates) {
            AiCompletionResult result = provider.complete(snapshot, messages, jsonMode);
            if (result != null && result.success()) {
                if (!jsonMode) {
                    responseCache.put(snapshot.cacheScope(), messages, result.content());
                }
                return result;
            }
            last = result;
            log.warn("【AI网关】协议 {} 调用失败 type={}", provider.name(),
                    result == null ? "NULL" : result.errorType());
            if ("MANAGED".equals(snapshot.mode())) {
                break;
            }
        }
        return last == null
                ? AiCompletionResult.failure(snapshot.source(), "MODEL_UNAVAILABLE", "AI 调用失败")
                : last;
    }

    public AiStreamResult stream(JSONArray messages, Consumer<String> onChunk) {
        AiServiceSnapshot snapshot;
        try {
            snapshot = runtimeResolver.resolveCurrent();
        } catch (RuntimeException e) {
            log.error("【AI网关】运行时配置读取失败 type={}", e.getClass().getSimpleName());
            return AiStreamResult.failure(AiCallSource.unavailable(), false, "MODEL_UNAVAILABLE",
                    "AI 运行配置读取失败");
        }
        if (!snapshot.available()) {
            return AiStreamResult.failure(snapshot.source(), false,
                    snapshot.unavailableType() == null ? "MODEL_UNAVAILABLE" : snapshot.unavailableType(),
                    snapshot.unavailableMessage() == null ? "AI 服务当前不可用" : snapshot.unavailableMessage());
        }
        List<AiProvider> candidates = candidates(snapshot);
        if (candidates.isEmpty()) {
            return AiStreamResult.failure(snapshot.source(), false, "INVALID_RESPONSE",
                    "没有匹配的 AI 协议适配器");
        }

        AiStreamResult last = null;
        for (AiProvider provider : candidates) {
            AiStreamResult result = provider.stream(snapshot, messages, onChunk);
            if (result != null && result.success()) {
                return result;
            }
            last = result;
            log.warn("【AI网关】协议 {} 流式失败 type={} emitted={}", provider.name(),
                    result == null ? "NULL" : result.errorType(),
                    result != null && result.emitted());
            if ((result != null && (result.emitted() || "CANCELLED".equals(result.errorType()))) || "MANAGED".equals(snapshot.mode())) {
                break;
            }
        }
        return last == null
                ? AiStreamResult.failure(snapshot.source(), false, "MODEL_UNAVAILABLE", "AI 流式调用失败")
                : last;
    }

    private List<AiProvider> candidates(AiServiceSnapshot snapshot) {
        return providers.stream()
                .filter(provider -> snapshot.protocol() != null && snapshot.protocol().equals(provider.protocol()))
                .toList();
    }

    private static AiCompletionResult unavailableCompletion(AiServiceSnapshot snapshot) {
        return AiCompletionResult.failure(snapshot.source(),
                snapshot.unavailableType() == null ? "MODEL_UNAVAILABLE" : snapshot.unavailableType(),
                snapshot.unavailableMessage() == null ? "AI 服务当前不可用" : snapshot.unavailableMessage());
    }
}
