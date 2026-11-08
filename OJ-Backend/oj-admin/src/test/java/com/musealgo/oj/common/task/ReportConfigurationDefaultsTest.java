package com.musealgo.oj.common.task;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Deployment defaults are part of the 2026-10-29 decisions: an operator who sets nothing must not get
 * automatic weekly reports, and pre-lease GENERATING jobs must never be recovered without an explicit
 * opt-in. These assertions inspect the packaged configuration, not a test-only override.
 */
class ReportConfigurationDefaultsTest {

    @Test
    void applicationDefaultsToDisabledAutomaticWeeklyReports() throws IOException {
        assertEquals("${OJ_REPORT_WEEKLY_ENABLED:false}", property("application.yml", "report.weekly-enabled"));
    }

    @Test
    void workerProfileDoesNotEnableWeeklyReportsByDefault() throws IOException {
        assertEquals("${OJ_REPORT_WEEKLY_ENABLED:false}",
                property("application-worker.yml", "report.weekly-enabled"));
    }

    @Test
    void legacyGeneratingRecoveryStaysOptIn() throws IOException {
        assertEquals("${OJ_REPORT_WORKER_RECOVER_LEGACY:false}",
                property("application.yml", "report.worker.recover-legacy-generating"));
    }

    private static String property(String resource, String key) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        String value = sources.stream()
                .map(source -> source.getProperty(key))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .findFirst()
                .orElse(null);
        assertNotNull(value, key + " missing from " + resource);
        return value;
    }
}
