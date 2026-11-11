package com.musealgo.oj.ai.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.musealgo.oj.ai.adminai.AiAdminException;
import com.musealgo.oj.ai.adminai.AiServiceAdminDtos;
import com.musealgo.oj.ai.adminai.AiServiceAdminDtos.RuntimeVo;
import com.musealgo.oj.ai.adminai.AiServiceAdminDtos.ServiceVo;
import com.musealgo.oj.ai.config.DynamicAiConfig;
import com.musealgo.oj.ai.crypto.AiKeyCipher;
import com.musealgo.oj.ai.crypto.AiKeyException;
import com.musealgo.oj.ai.entity.AiRuntimeConfig;
import com.musealgo.oj.ai.entity.AiServiceConfig;
import com.musealgo.oj.ai.mapper.AiRuntimeConfigMapper;
import com.musealgo.oj.ai.mapper.AiServiceConfigMapper;
import com.musealgo.oj.ai.network.AiEndpointGuard;
import com.musealgo.oj.ai.runtime.AiRuntimeConfigResolver;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * AI 服务目录与运行时选择的写模型。
 *
 * <p>所有涉及运行时和服务两条记录的事务统一先锁运行时行、再锁服务行，避免切换、编辑、
 * 删除并发时产生指向已删除服务或覆盖新版本的状态。远程连接测试不在本类事务内执行。</p>
 */
@Service
public class AiServiceCatalogService {

    private final AiServiceConfigMapper serviceMapper;
    private final AiRuntimeConfigMapper runtimeMapper;
    private final AiKeyCipher keyCipher;
    private final AiEndpointGuard endpointGuard;
    private final AiRuntimeConfigResolver runtimeResolver;
    private final DynamicAiConfig dynamicAiConfig;
    private final TransactionTemplate tx;

    public AiServiceCatalogService(AiServiceConfigMapper serviceMapper,
                                   AiRuntimeConfigMapper runtimeMapper,
                                   AiKeyCipher keyCipher,
                                   AiEndpointGuard endpointGuard,
                                   AiRuntimeConfigResolver runtimeResolver,
                                   DynamicAiConfig dynamicAiConfig,
                                   PlatformTransactionManager transactionManager) {
        this.serviceMapper = serviceMapper;
        this.runtimeMapper = runtimeMapper;
        this.keyCipher = keyCipher;
        this.endpointGuard = endpointGuard;
        this.runtimeResolver = runtimeResolver;
        this.dynamicAiConfig = dynamicAiConfig;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public List<ServiceVo> list() {
        AiRuntimeConfig runtime = runtimeResolver.readRuntime();
        return serviceMapper.selectAllActive().stream()
                .map(service -> toVo(service, runtime))
                .toList();
    }

    public ServiceVo create(AiServiceAdminDtos.CreateRequest request, Long actorId) {
        Fields fields = validateFields(request);
        if (!request.isApiKeyProvided()) {
            throw new AiAdminException(400, "新增服务必须提供 API 密钥");
        }
        String key = requireApiKey(request.getApiKey());
        String encrypted;
        try {
            encrypted = keyCipher.encrypt(key);
        } catch (AiKeyException e) {
            throw new AiAdminException(503, e.errorCode(), e.getMessage());
        }
        AiServiceConfig service = new AiServiceConfig();
        applyFields(service, fields);
        service.setApiKeyEncrypted(encrypted);
        service.setEncryptionVersion("v1");
        service.setEnabled(true);
        service.setVersion(1);
        service.setDeleted(false);
        service.setCreatedBy(actorId);
        service.setUpdatedBy(actorId);
        LocalDateTime now = LocalDateTime.now();
        service.setCreatedAt(now);
        service.setUpdatedAt(now);
        serviceMapper.insert(service);
        return toVo(service, runtimeResolver.readRuntime());
    }

    /**
     * 编辑备选服务；当前服务必须走「测试并保存应用」的事务外测试路径（P2 接入）。
     * 客户端编辑正在使用的服务时也会在此被拒绝，不会出现半更新。
     */
    public ServiceVo update(Long id, AiServiceAdminDtos.UpdateRequest request, Long actorId) {
        AiServiceConfig existing = requireService(id);
        AiRuntimeConfig runtime = runtimeResolver.readRuntime();
        if (isCurrent(serviceId(runtime), existing.getId())) {
            throw new AiAdminException(409, "当前服务必须通过“测试并保存应用”修改");
        }
        Fields fields = validateFields(request);
        String newCipher = null;
        if (request.isApiKeyProvided()) {
            String key = requireApiKey(request.getApiKey());
            try {
                newCipher = keyCipher.encrypt(key);
            } catch (AiKeyException e) {
                throw new AiAdminException(503, e.errorCode(), e.getMessage());
            }
        }
        String finalCipher = newCipher;
        AiServiceConfig updated = tx.execute(status -> {
            AiRuntimeConfig lockedRuntime = lockRuntime();
            AiServiceConfig locked = lockService(id);
            if (isCurrent(serviceId(lockedRuntime), locked.getId())) {
                throw new AiAdminException(409, "当前服务必须通过“测试并保存应用”修改");
            }
            requireVersion(locked.getVersion(), request.getVersion());
            applyFields(locked, fields);
            if (finalCipher != null) {
                locked.setApiKeyEncrypted(finalCipher);
                locked.setEncryptionVersion("v1");
            }
            locked.setVersion(locked.getVersion() + 1);
            locked.setUpdatedBy(actorId);
            locked.setUpdatedAt(LocalDateTime.now());
            serviceMapper.updateById(locked);
            return locked;
        });
        if (updated == null) {
            throw new IllegalStateException("AI 服务更新事务未返回记录");
        }
        return toVo(updated, runtimeResolver.readRuntime());
    }

    public ServiceVo changeEnabled(Long id, int expectedVersion, boolean enabled, Long actorId) {
        AiServiceConfig updated = tx.execute(status -> {
            AiRuntimeConfig lockedRuntime = lockRuntime();
            AiServiceConfig locked = lockService(id);
            requireVersion(locked.getVersion(), expectedVersion);
            if (!enabled && isCurrent(serviceId(lockedRuntime), locked.getId())) {
                throw new AiAdminException(409, "当前服务不能直接停用，请先切换或停用全站 AI");
            }
            locked.setEnabled(enabled);
            locked.setUpdatedBy(actorId);
            locked.setUpdatedAt(LocalDateTime.now());
            serviceMapper.updateById(locked);
            return locked;
        });
        return toVo(updated, runtimeResolver.readRuntime());
    }

    public void delete(Long id, int expectedVersion, Long actorId) {
        tx.executeWithoutResult(status -> {
            AiRuntimeConfig lockedRuntime = lockRuntime();
            AiServiceConfig locked = lockService(id);
            requireVersion(locked.getVersion(), expectedVersion);
            if (isCurrent(serviceId(lockedRuntime), locked.getId())) {
                throw new AiAdminException(409, "当前服务不能删除，请先切换或停用全站 AI");
            }
            locked.setDeleted(true);
            locked.setEnabled(false);
            locked.setUpdatedBy(actorId);
            locked.setUpdatedAt(LocalDateTime.now());
            serviceMapper.updateById(locked);
        });
    }

    /** 只允许 LEGACY/OFF；进入 MANAGED 必须经过带测试校验的 activate。 */
    public RuntimeVo updateRuntime(String mode, int expectedVersion, Long actorId) {
        String normalized = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        if (!AiRuntimeConfig.MODE_LEGACY.equals(normalized) && !AiRuntimeConfig.MODE_OFF.equals(normalized)) {
            throw new AiAdminException(400, "运行模式只允许 LEGACY 或 OFF；切换到已测试服务请使用激活接口");
        }
        tx.executeWithoutResult(status -> {
            AiRuntimeConfig runtime = lockRuntime();
            requireVersion(runtime.getVersion(), expectedVersion);
            runtime.setMode(normalized);
            runtime.setCurrentServiceId(null);
            runtime.setVersion(runtime.getVersion() + 1);
            runtime.setUpdatedBy(actorId);
            runtime.setUpdatedAt(LocalDateTime.now());
            runtimeMapper.updateById(runtime);
        });
        return runtimeView();
    }

    public record AddressDiagnosis(String host, boolean masterKeyReady, String message) { }

    public AddressDiagnosis diagnose(String apiUrl) {
        try {
            URI endpoint = endpointGuard.validate(apiUrl);
            return new AddressDiagnosis(endpoint.getHost(), keyCipher.readiness().ready(),
                    "地址解析与安全检查通过；尚未验证服务凭证及模型，请保存后测试连接");
        } catch (com.musealgo.oj.ai.network.AiEndpointException e) {
            throw new AiAdminException(e.status(), e.errorCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new AiAdminException(400, e.getMessage());
        }
    }

    public RuntimeVo runtimeView() {
        AiRuntimeConfig runtime = runtimeResolver.readRuntime();
        AiServiceConfig current = runtime.getCurrentServiceId() == null
                ? null
                : runtimeResolver.findActiveService(runtime.getCurrentServiceId());
        String mode = runtime.getMode();
        return new RuntimeVo(
                mode,
                current == null ? null : current.getId(),
                current == null ? null : current.getName(),
                current == null ? null : current.getModel(),
                current == null ? null : current.getVersion(),
                current != null && current.hasPassingTestForCurrentVersion(),
                runtime.getVersion(),
                dynamicAiConfig.model(), keyCipher.readiness());
    }

    ServiceVo toVo(AiServiceConfig service, AiRuntimeConfig runtime) {
        boolean current = isCurrent(serviceId(runtime), service.getId());
        String usageStatus = current ? "CURRENT" : Boolean.TRUE.equals(service.getEnabled()) ? "AVAILABLE" : "DISABLED";
        String testState;
        if (service.getLastTestStatus() == null || service.getLastTestVersion() == null) {
            testState = "UNTESTED";
        } else if (!service.getLastTestVersion().equals(service.getVersion())) {
            testState = "STALE";
        } else {
            testState = service.getLastTestStatus();
        }
        return new ServiceVo(
                service.getId(),
                service.getName(),
                service.getProtocol(),
                service.getApiUrl(),
                service.getModel(),
                service.getApiKeyEncrypted() != null && !service.getApiKeyEncrypted().isBlank(),
                Boolean.TRUE.equals(service.getEnabled()),
                usageStatus,
                Boolean.TRUE.equals(service.getJsonMode()),
                Boolean.TRUE.equals(service.getStreamIncludeUsage()),
                service.getMaxTokens() == null ? 8192 : service.getMaxTokens(),
                service.getRemark(),
                service.getVersion(),
                service.getLastTestVersion(),
                service.getLastTestAt(),
                service.getLastTestStatus(),
                testState,
                service.getCreatedAt(),
                service.getUpdatedAt());
    }

    AiServiceConfig requireService(Long id) {
        if (id == null || id <= 0) {
            throw new AiAdminException(404, "AI 服务不存在");
        }
        AiServiceConfig service = serviceMapper.selectActiveById(id);
        if (service == null) {
            throw new AiAdminException(404, "AI 服务不存在或已删除");
        }
        return service;
    }

    AiRuntimeConfig lockRuntime() {
        AiRuntimeConfig runtime = runtimeMapper.lockSingleton();
        if (runtime == null) {
            throw new IllegalStateException("AI 运行时配置缺少单例行");
        }
        return runtime;
    }

    AiServiceConfig lockService(Long id) {
        AiServiceConfig service = serviceMapper.selectActiveByIdForUpdate(id);
        if (service == null) {
            throw new AiAdminException(404, "AI 服务不存在或已删除");
        }
        return service;
    }

    static void requireVersion(Integer actual, Integer expected) {
        if (expected == null || !expected.equals(actual)) {
            throw new AiAdminException(409, "配置已被其他人修改，请刷新后重试");
        }
    }

    static boolean isCurrent(Long currentServiceId, Long candidateId) {
        return currentServiceId != null && currentServiceId.equals(candidateId);
    }

    private static Long serviceId(AiRuntimeConfig runtime) {
        return AiRuntimeConfig.MODE_MANAGED.equals(runtime.getMode()) ? runtime.getCurrentServiceId() : null;
    }

    Fields validateFields(AiServiceAdminDtos.ServiceFieldsRequest request) {
        String name = requireTrimmed(request.getName(), "服务名称不能为空", 50);
        String protocol = request.getProtocol() == null || request.getProtocol().isBlank()
                ? AiServiceConfig.PROTOCOL_CHAT_COMPLETIONS
                : request.getProtocol().trim().toUpperCase(Locale.ROOT);
        if (!AiServiceConfig.PROTOCOL_CHAT_COMPLETIONS.equals(protocol)) {
            throw new AiAdminException(400, "首版只支持 Chat Completions 兼容协议");
        }
        URI endpoint;
        try {
            endpoint = endpointGuard.validate(request.getApiUrl());
        } catch (com.musealgo.oj.ai.network.AiEndpointException e) {
            throw new AiAdminException(e.status(), e.errorCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new AiAdminException(400, e.getMessage());
        }
        String model = requireTrimmed(request.getModel(), "模型名称不能为空", 128);
        String remark = request.getRemark() == null ? null : request.getRemark().trim();
        if (remark != null && remark.isEmpty()) {
            remark = null;
        }
        if (remark != null && remark.length() > 500) {
            throw new AiAdminException(400, "备注不能超过 500 个字符");
        }
        int maxTokens = request.getMaxTokens() == null ? 8192 : request.getMaxTokens();
        if (maxTokens < 1 || maxTokens > 65536) {
            throw new AiAdminException(400, "输出上限必须在 1–65536 之间");
        }
        boolean jsonMode = request.getJsonMode() == null || request.getJsonMode();
        boolean includeUsage = request.getStreamIncludeUsage() == null || request.getStreamIncludeUsage();
        return new Fields(name, protocol, endpoint.toString(), model, remark, jsonMode, includeUsage, maxTokens);
    }

    private static String requireTrimmed(String value, String emptyMessage, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new AiAdminException(400, emptyMessage);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new AiAdminException(400, emptyMessage.replace("不能为空", "过长"));
        }
        return trimmed;
    }

    static String requireApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.trim().isEmpty()) {
            throw new AiAdminException(400, "API 密钥不能为空");
        }
        if (!apiKey.equals(apiKey.trim()) || apiKey.length() > 4096) {
            throw new AiAdminException(400, "API 密钥格式非法");
        }
        boolean placeholder = apiKey.chars().allMatch(ch -> ch == '*' || ch == '•' || ch == '●' || ch == '·');
        if (placeholder) {
            throw new AiAdminException(400, "API 密钥不能使用星号等占位字符");
        }
        for (int i = 0; i < apiKey.length(); i++) {
            if (Character.isISOControl(apiKey.charAt(i))) {
                throw new AiAdminException(400, "API 密钥含非法字符");
            }
        }
        return apiKey;
    }

    static void applyFields(AiServiceConfig service, Fields fields) {
        service.setName(fields.name());
        service.setProtocol(fields.protocol());
        service.setApiUrl(fields.apiUrl());
        service.setModel(fields.model());
        service.setRemark(fields.remark());
        service.setJsonMode(fields.jsonMode());
        service.setStreamIncludeUsage(fields.includeUsage());
        service.setMaxTokens(fields.maxTokens());
    }

    record Fields(String name, String protocol, String apiUrl, String model,
                  String remark, boolean jsonMode, boolean includeUsage, int maxTokens) {
    }
}
