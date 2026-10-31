package com.musealgo.oj.ai.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.musealgo.oj.ai.config.DynamicAiConfig;
import com.musealgo.oj.ai.crypto.AiKeyCipher;
import com.musealgo.oj.ai.crypto.AiKeyException;
import com.musealgo.oj.ai.entity.AiRuntimeConfig;
import com.musealgo.oj.ai.entity.AiServiceConfig;
import com.musealgo.oj.ai.mapper.AiRuntimeConfigMapper;
import com.musealgo.oj.ai.mapper.AiServiceConfigMapper;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 每次请求从数据库解析运行时配置，不做进程内缓存。
 *
 * <p>只读事务让运行时行与服务行处于同一数据库快照，避免刚切换就混用两条记录。
 * MANAGED 解密失败或记录不可用时返回不可用快照，调用方不得回退到环境变量。</p>
 */
@Slf4j
@Component
public class AiRuntimeConfigResolver {

    private final AiRuntimeConfigMapper runtimeMapper;
    private final AiServiceConfigMapper serviceMapper;
    private final DynamicAiConfig dynamicAiConfig;
    private final AiKeyCipher keyCipher;
    private final TransactionTemplate readOnlyTx;

    @Value("${ai.api-url:https://api.deepseek.com/v1/chat/completions}")
    private String legacyApiUrl;

    @Value("${ai.api-key:}")
    private String legacyApiKey;

    public AiRuntimeConfigResolver(AiRuntimeConfigMapper runtimeMapper,
                                   AiServiceConfigMapper serviceMapper,
                                   DynamicAiConfig dynamicAiConfig,
                                   AiKeyCipher keyCipher,
                                   PlatformTransactionManager transactionManager) {
        this.runtimeMapper = runtimeMapper;
        this.serviceMapper = serviceMapper;
        this.dynamicAiConfig = dynamicAiConfig;
        this.keyCipher = keyCipher;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    /** 解析当前请求快照；数据库失败会抛出，由网关按本次调用失败处理。 */
    public AiServiceSnapshot resolveCurrent() {
        AiServiceSnapshot snapshot = readOnlyTx.execute(status -> {
            AiRuntimeConfig runtime = runtimeMapper.selectSingleton();
            if (runtime == null) {
                throw new IllegalStateException("AI 运行时配置缺少单例行");
            }
            if (AiRuntimeConfig.MODE_OFF.equals(runtime.getMode())) {
                return AiServiceSnapshot.off();
            }
            if (AiRuntimeConfig.MODE_LEGACY.equals(runtime.getMode())) {
                return legacySnapshot();
            }
            if (!AiRuntimeConfig.MODE_MANAGED.equals(runtime.getMode()) || runtime.getCurrentServiceId() == null) {
                return AiServiceSnapshot.unavailableManaged(null, "运行时配置", null, "unavailable",
                        0, "INVALID_RESPONSE", "AI 运行时配置无效");
            }
            AiServiceConfig service = serviceMapper.selectActiveById(runtime.getCurrentServiceId());
            if (service == null) {
                return AiServiceSnapshot.unavailableManaged(runtime.getCurrentServiceId(), "未知服务",
                        null, "unavailable", 0, "MODEL_UNAVAILABLE", "当前 AI 服务不存在");
            }
            if (!Boolean.TRUE.equals(service.getEnabled())) {
                return AiServiceSnapshot.unavailableManaged(service.getId(), service.getName(),
                        service.getProtocol(), service.getModel(), service.getVersion(),
                        "MODEL_UNAVAILABLE", "当前 AI 服务已停用");
            }
            try {
                String key = keyCipher.decrypt(service.getApiKeyEncrypted(), service.getEncryptionVersion());
                URI endpoint = new URI(service.getApiUrl());
                return AiServiceSnapshot.managed(service.getId(), service.getName(), service.getProtocol(),
                        endpoint, service.getModel(), key, service.getVersion(),
                        Boolean.TRUE.equals(service.getJsonMode()),
                        Boolean.TRUE.equals(service.getStreamIncludeUsage()),
                        service.getMaxTokens());
            } catch (AiKeyException e) {
                log.warn("【AI运行时】服务密钥不可用 serviceId={} reason={}", service.getId(), e.getMessage());
                return AiServiceSnapshot.unavailableManaged(service.getId(), service.getName(),
                        service.getProtocol(), service.getModel(), service.getVersion(),
                        "CRYPTO_UNAVAILABLE", "AI 服务密钥不可用");
            } catch (URISyntaxException e) {
                return AiServiceSnapshot.unavailableManaged(service.getId(), service.getName(),
                        service.getProtocol(), service.getModel(), service.getVersion(),
                        "INVALID_RESPONSE", "AI 服务接口地址无效");
            }
        });
        if (snapshot == null) {
            throw new IllegalStateException("AI 运行时配置解析为空");
        }
        return snapshot;
    }

    /** 管理页读取运行时行；不在请求链路使用。 */
    public AiRuntimeConfig readRuntime() {
        AiRuntimeConfig runtime = readOnlyTx.execute(status -> runtimeMapper.selectSingleton());
        if (runtime == null) {
            throw new IllegalStateException("AI 运行时配置缺少单例行");
        }
        return runtime;
    }

    public AiServiceConfig findActiveService(Long id) {
        return readOnlyTx.execute(status -> serviceMapper.selectActiveById(id));
    }

    private AiServiceSnapshot legacySnapshot() {
        try {
            return AiServiceSnapshot.legacy(new URI(legacyApiUrl), dynamicAiConfig.model(), legacyApiKey,
                    dynamicAiConfig.jsonMode(), dynamicAiConfig.maxTokens());
        } catch (URISyntaxException e) {
            return AiServiceSnapshot.unavailableLegacy(dynamicAiConfig.model(),
                    "INVALID_RESPONSE", "旧环境 AI 接口地址无效");
        }
    }
}
