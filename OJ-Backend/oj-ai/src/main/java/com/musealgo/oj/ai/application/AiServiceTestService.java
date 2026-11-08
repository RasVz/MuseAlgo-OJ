package com.musealgo.oj.ai.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.musealgo.oj.ai.adminai.AiAdminException;
import com.musealgo.oj.ai.adminai.AiServiceAdminDtos;
import com.musealgo.oj.ai.adminai.AiServiceProbe;
import com.musealgo.oj.ai.crypto.AiKeyCipher;
import com.musealgo.oj.ai.crypto.AiKeyException;
import com.musealgo.oj.ai.entity.AiServiceConfig;
import com.musealgo.oj.ai.gateway.ChatCompletionsClient;
import com.musealgo.oj.ai.mapper.AiServiceConfigMapper;
import com.musealgo.oj.ai.runtime.AiServiceSnapshot;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 连接测试编排：远程调用不持有数据库锁，结束后只接受发起时版本的测试结果。
 * 测试调用不写 ai_usage，也不占学生每日配额。
 */
@Service
public class AiServiceTestService {

    private static final int MAX_CONCURRENT_TESTS = 4;

    private final AiServiceCatalogService catalogService;
    private final AiServiceConfigMapper serviceMapper;
    private final AiKeyCipher keyCipher;
    private final AiServiceProbe probe;
    private final ChatCompletionsClient probeClient;
    private final TransactionTemplate tx;

    private final Semaphore testSlots = new Semaphore(MAX_CONCURRENT_TESTS, true);
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public AiServiceTestService(AiServiceCatalogService catalogService,
                                AiServiceConfigMapper serviceMapper,
                                AiKeyCipher keyCipher,
                                AiServiceProbe probe,
                                ChatCompletionsClient chatClient,
                                PlatformTransactionManager transactionManager) {
        this.catalogService = catalogService;
        this.serviceMapper = serviceMapper;
        this.keyCipher = keyCipher;
        this.probe = probe;
        this.probeClient = chatClient.withTimeouts(5, 20, 30);
        this.tx = new TransactionTemplate(transactionManager);
    }

    public AiServiceAdminDtos.TestReport test(Long serviceId, int expectedVersion, Long actorId) {
        return withTestSlot(serviceId, () -> {
            AiServiceConfig service = catalogService.requireService(serviceId);
            AiServiceCatalogService.requireVersion(service.getVersion(), expectedVersion);
            AiServiceAdminDtos.TestReport report = probeSaved(service, expectedVersion);
            persistResult(serviceId, expectedVersion, report, actorId);
            return report;
        });
    }

    public AiServiceAdminDtos.TestReport probeCandidate(AiServiceConfig candidate, String plaintextKey) {
        return withTestSlot(candidate.getId(), () -> {
            AiServiceSnapshot snapshot;
            try {
                URI endpoint = new URI(candidate.getApiUrl());
                snapshot = AiServiceSnapshot.managed(candidate.getId(), candidate.getName(), candidate.getProtocol(),
                        endpoint, candidate.getModel(), plaintextKey, candidate.getVersion(),
                        Boolean.TRUE.equals(candidate.getJsonMode()),
                        Boolean.TRUE.equals(candidate.getStreamIncludeUsage()),
                        candidate.getMaxTokens());
            } catch (URISyntaxException e) {
                return probe.cryptoUnavailable(candidate.getId(), candidate.getVersion(), "AI 服务接口地址无效");
            }
            return probe.probe(snapshot, candidate.getVersion(), probeClient);
        });
    }

    private AiServiceAdminDtos.TestReport probeSaved(AiServiceConfig service, int expectedVersion) {
        String plaintextKey;
        try {
            plaintextKey = keyCipher.decrypt(service.getApiKeyEncrypted(), service.getEncryptionVersion());
        } catch (AiKeyException e) {
            return probe.cryptoUnavailable(service.getId(), expectedVersion, e.getMessage());
        }
        try {
            URI endpoint = new URI(service.getApiUrl());
            AiServiceSnapshot snapshot = AiServiceSnapshot.managed(service.getId(), service.getName(),
                    service.getProtocol(), endpoint, service.getModel(), plaintextKey, service.getVersion(),
                    Boolean.TRUE.equals(service.getJsonMode()),
                    Boolean.TRUE.equals(service.getStreamIncludeUsage()),
                    service.getMaxTokens());
            return probe.probe(snapshot, expectedVersion, probeClient);
        } catch (URISyntaxException e) {
            return probe.cryptoUnavailable(service.getId(), expectedVersion, "AI 服务接口地址无效");
        }
    }

    private void persistResult(Long serviceId, int expectedVersion,
                               AiServiceAdminDtos.TestReport report, Long actorId) {
        tx.executeWithoutResult(status -> {
            catalogService.lockRuntime();
            AiServiceConfig locked = catalogService.lockService(serviceId);
            AiServiceCatalogService.requireVersion(locked.getVersion(), expectedVersion);
            locked.setLastTestVersion(expectedVersion);
            locked.setLastTestAt(report.testedAt() == null ? LocalDateTime.now() : report.testedAt());
            locked.setLastTestStatus(report.passed()
                    ? AiServiceConfig.TEST_PASSED : AiServiceConfig.TEST_FAILED);
            locked.setUpdatedBy(actorId);
            serviceMapper.updateById(locked);
        });
    }

    private <T> T withTestSlot(Long serviceId, Supplier<T> operation) {
        if (serviceId == null) {
            throw new AiAdminException(400, "AI 服务 ID 无效");
        }
        if (!testSlots.tryAcquire()) {
            throw new AiAdminException(429, "连接测试并发已满，请稍后重试");
        }
        boolean registered = false;
        try {
            if (!inFlight.add(serviceId)) {
                throw new AiAdminException(409, "该服务正在测试，请勿重复提交");
            }
            registered = true;
            return operation.get();
        } finally {
            if (registered) {
                inFlight.remove(serviceId);
            }
            testSlots.release();
        }
    }
}
