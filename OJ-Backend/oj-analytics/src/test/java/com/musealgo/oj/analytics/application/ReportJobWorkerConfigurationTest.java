package com.musealgo.oj.analytics.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The annotation fallback is the last line of defence when a configuration source omits the key.
 * Pre-lease GENERATING rows may only be recovered after an operator confirms the old worker stopped,
 * so the fail-safe default must stay {@code false} even without application.yml.
 */
class ReportJobWorkerConfigurationTest {

    @Test
    void legacyGeneratingRecoveryFallsBackToDisabled() throws Exception {
        Field field = ReportJobWorker.class.getDeclaredField("recoverLegacyGenerating");
        Value value = field.getAnnotation(Value.class);

        assertEquals("${report.worker.recover-legacy-generating:false}", value.value());
    }
}
