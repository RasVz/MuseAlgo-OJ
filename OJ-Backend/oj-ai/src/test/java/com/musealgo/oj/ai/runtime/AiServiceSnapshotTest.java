package com.musealgo.oj.ai.runtime;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiServiceSnapshotTest {

    @Test
    void cacheScopeChangesWithServiceVersionAndModel() {
        AiServiceSnapshot v1 = AiServiceSnapshot.managed(1L, "主服务", "CHAT_COMPLETIONS",
                URI.create("https://api.example.com/v1/chat/completions"), "model-a", "secret",
                1, false, true, 8192);
        AiServiceSnapshot v2 = AiServiceSnapshot.managed(1L, "主服务", "CHAT_COMPLETIONS",
                URI.create("https://api.example.com/v1/chat/completions"), "model-a", "secret",
                2, false, true, 8192);
        AiServiceSnapshot otherModel = AiServiceSnapshot.managed(1L, "主服务", "CHAT_COMPLETIONS",
                URI.create("https://api.example.com/v1/chat/completions"), "model-b", "secret",
                1, false, true, 8192);

        assertNotEquals(v1.cacheScope(), v2.cacheScope());
        assertNotEquals(v1.cacheScope(), otherModel.cacheScope());
        assertEquals("MANAGED", v1.source().runtimeMode());
        assertEquals(1, v1.source().configVersion());
    }

    @Test
    void offSnapshotNeverExposesInsteadOfCallingFallback() {
        AiServiceSnapshot off = AiServiceSnapshot.off();

        assertFalse(off.available());
        assertEquals("OFF", off.source().runtimeMode());
        assertEquals("AI 已停用", off.source().serviceName());
    }

    @Test
    void toStringDoesNotContainKeyOrUrl() {
        AiServiceSnapshot snapshot = AiServiceSnapshot.managed(7L, "测试服务", "CHAT_COMPLETIONS",
                URI.create("https://secret-host.example.com/v1/chat/completions"), "model-a",
                "super-secret-key", 3, true, true, 1024);

        assertFalse(snapshot.toString().contains("super-secret-key"));
        assertFalse(snapshot.toString().contains("secret-host"));
        assertTrue(snapshot.toString().contains("serviceId=7"));
    }

    @Test
    void legacyWithoutKeyIsUnavailableButKeepsSourceIdentity() {
        AiServiceSnapshot legacy = AiServiceSnapshot.legacy(
                URI.create("https://api.deepseek.com/v1/chat/completions"), "deepseek-chat", "", true, 8192);

        assertFalse(legacy.available());
        assertEquals("NOT_CONFIGURED", legacy.unavailableType());
        assertEquals("LEGACY", legacy.source().runtimeMode());
    }
}
