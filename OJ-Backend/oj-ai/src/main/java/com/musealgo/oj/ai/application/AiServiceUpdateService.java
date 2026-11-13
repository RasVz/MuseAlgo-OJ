package com.musealgo.oj.ai.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.musealgo.oj.ai.adminai.AiAdminException;
import com.musealgo.oj.ai.adminai.AiServiceAdminDtos;
import com.musealgo.oj.ai.crypto.AiKeyCipher;
import com.musealgo.oj.ai.crypto.AiKeyException;
import com.musealgo.oj.ai.entity.AiRuntimeConfig;
import com.musealgo.oj.ai.entity.AiServiceConfig;
import com.musealgo.oj.ai.mapper.AiRuntimeConfigMapper;
import com.musealgo.oj.ai.mapper.AiServiceConfigMapper;
import com.musealgo.oj.ai.runtime.AiRuntimeConfigResolver;

import java.time.LocalDateTime;

/**
 * 统一编辑与切换入口。
 *
 * <p>备选编辑走普通版本保存；当前编辑先在事务外测试候选配置，再在同一行锁顺序下复核
 * 服务版本、运行时版本和当前服务 ID，全部未变才保存并应用。测试失败不触碰线上记录。</p>
 */
@Service
public class AiServiceUpdateService {

    private final AiServiceCatalogService catalogService;
    private final AiServiceTestService testService;
    private final AiServiceConfigMapper serviceMapper;
    private final AiRuntimeConfigMapper runtimeMapper;
    private final AiRuntimeConfigResolver runtimeResolver;
    private final AiKeyCipher keyCipher;
    private final TransactionTemplate tx;

    public AiServiceUpdateService(AiServiceCatalogService catalogService,
                                  AiServiceTestService testService,
                                  AiServiceConfigMapper serviceMapper,
                                  AiRuntimeConfigMapper runtimeMapper,
                                  AiRuntimeConfigResolver runtimeResolver,
                                  AiKeyCipher keyCipher,
                                  PlatformTransactionManager transactionManager) {
        this.catalogService = catalogService;
        this.testService = testService;
        this.serviceMapper = serviceMapper;
        this.runtimeMapper = runtimeMapper;
        this.runtimeResolver = runtimeResolver;
        this.keyCipher = keyCipher;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public AiServiceAdminDtos.UpdateResponse update(Long id, AiServiceAdminDtos.UpdateRequest request, Long actorId) {
        AiServiceConfig existing = catalogService.requireService(id);
        AiRuntimeConfig runtime = runtimeResolver.readRuntime();
        if (!AiServiceCatalogService.isCurrent(runtime.getCurrentServiceId(), id)) {
            AiServiceAdminDtos.ServiceVo updated = catalogService.update(id, request, actorId);
            return new AiServiceAdminDtos.UpdateResponse(updated, null, true);
        }
        return updateCurrent(existing, runtime, request, actorId);
    }

    public AiServiceAdminDtos.RuntimeVo activate(Long id,
                                                 int expectedServiceVersion,
                                                 int expectedRuntimeVersion,
                                                 Long actorId) {
        tx.executeWithoutResult(status -> {
            AiRuntimeConfig runtime = catalogService.lockRuntime();
            AiServiceConfig service = catalogService.lockService(id);
            AiServiceCatalogService.requireVersion(runtime.getVersion(), expectedRuntimeVersion);
            AiServiceCatalogService.requireVersion(service.getVersion(), expectedServiceVersion);
            if (!Boolean.TRUE.equals(service.getEnabled())) {
                throw new AiAdminException(409, "服务已停用，不能设为当前服务");
            }
            if (!service.hasPassingTestForCurrentVersion()) {
                throw new AiAdminException(409, "当前版本尚未通过连接测试，请先测试");
            }
            runtime.setMode(AiRuntimeConfig.MODE_MANAGED);
            runtime.setCurrentServiceId(service.getId());
            runtime.setVersion(runtime.getVersion() + 1);
            runtime.setUpdatedBy(actorId);
            runtime.setUpdatedAt(LocalDateTime.now());
            runtimeMapper.updateById(runtime);
        });
        return catalogService.runtimeView();
    }

    private AiServiceAdminDtos.UpdateResponse updateCurrent(AiServiceConfig existing,
                                                            AiRuntimeConfig runtime,
                                                            AiServiceAdminDtos.UpdateRequest request,
                                                            Long actorId) {
        if (request.getRuntimeVersion() == null) {
            throw new AiAdminException(400, "编辑当前服务必须提供运行时版本号");
        }
        AiServiceCatalogService.requireVersion(runtime.getVersion(), request.getRuntimeVersion());
        AiServiceCatalogService.requireVersion(existing.getVersion(), request.getVersion());
        if (!AiRuntimeConfig.MODE_MANAGED.equals(runtime.getMode())
                || !existing.getId().equals(runtime.getCurrentServiceId())) {
            throw new AiAdminException(409, "当前服务已变化，请刷新后重试");
        }

        AiServiceCatalogService.Fields fields = catalogService.validateFields(request);
        String plaintextKey;
        String newCipher = null;
        try {
            if (request.isApiKeyProvided()) {
                plaintextKey = AiServiceCatalogService.requireApiKey(request.getApiKey());
                newCipher = keyCipher.encrypt(plaintextKey);
            } else {
                plaintextKey = keyCipher.decrypt(existing.getApiKeyEncrypted(), existing.getEncryptionVersion());
            }
        } catch (AiKeyException e) {
            throw new AiAdminException(503, e.errorCode(), e.getMessage());
        }

        int nextVersion = existing.getVersion() + 1;
        AiServiceConfig candidate = new AiServiceConfig();
        candidate.setId(existing.getId());
        candidate.setVersion(nextVersion);
        AiServiceCatalogService.applyFields(candidate, fields);
        AiServiceAdminDtos.TestReport report = testService.probeCandidate(candidate, plaintextKey);
        if (!report.passed()) {
            AiServiceAdminDtos.ServiceVo unchanged = catalogService.toVo(existing, runtime);
            return new AiServiceAdminDtos.UpdateResponse(unchanged, report, false);
        }

        String finalCipher = newCipher;
        Applied applied = tx.execute(status -> {
            AiRuntimeConfig lockedRuntime = catalogService.lockRuntime();
            AiServiceConfig lockedService = catalogService.lockService(existing.getId());
            AiServiceCatalogService.requireVersion(lockedRuntime.getVersion(), request.getRuntimeVersion());
            AiServiceCatalogService.requireVersion(lockedService.getVersion(), request.getVersion());
            if (!AiRuntimeConfig.MODE_MANAGED.equals(lockedRuntime.getMode())
                    || !lockedService.getId().equals(lockedRuntime.getCurrentServiceId())) {
                throw new AiAdminException(409, "当前服务已变化，请刷新后重试");
            }

            AiServiceCatalogService.applyFields(lockedService, fields);
            if (finalCipher != null) {
                lockedService.setApiKeyEncrypted(finalCipher);
                lockedService.setEncryptionVersion("v1");
            }
            lockedService.setVersion(nextVersion);
            lockedService.setLastTestVersion(nextVersion);
            lockedService.setLastTestAt(report.testedAt());
            lockedService.setLastTestStatus(AiServiceConfig.TEST_PASSED);
            lockedService.setUpdatedBy(actorId);
            lockedService.setUpdatedAt(LocalDateTime.now());
            serviceMapper.updateById(lockedService);

            lockedRuntime.setVersion(lockedRuntime.getVersion() + 1);
            lockedRuntime.setUpdatedBy(actorId);
            lockedRuntime.setUpdatedAt(LocalDateTime.now());
            runtimeMapper.updateById(lockedRuntime);
            return new Applied(lockedService, lockedRuntime);
        });
        if (applied == null) {
            throw new IllegalStateException("当前服务更新事务未返回记录");
        }
        return new AiServiceAdminDtos.UpdateResponse(
                catalogService.toVo(applied.service(), applied.runtime()), report, true);
    }

    private record Applied(AiServiceConfig service, AiRuntimeConfig runtime) {
    }
}
