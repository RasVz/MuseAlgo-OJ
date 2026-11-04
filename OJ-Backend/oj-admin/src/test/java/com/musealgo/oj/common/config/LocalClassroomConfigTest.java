package com.musealgo.oj.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class LocalClassroomConfigTest {
    @Test void explicitLocalProfileProvidesClassroomBudgetsAndShortPoolWait() throws Exception {
        var env = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load("classroom",
                new ClassPathResource("application-local-classroom.yml"))) {
            env.getPropertySources().addLast(source);
        }
        assertEquals("120", env.getProperty("oj.security.login.ip-max-attempts"));
        assertEquals("5", env.getProperty("oj.security.login.account-max-failures"));
        assertEquals("60", env.getProperty("oj.security.login.account-window-seconds"));
        assertEquals("60", env.getProperty("oj.security.login.account-cooldown-seconds"));
        assertEquals("2000", env.getProperty("spring.datasource.hikari.connection-timeout"));
        assertEquals("none", env.getProperty("server.forward-headers-strategy"));
        env.setProperty("OJ_DB_CONNECTION_TIMEOUT_MS", "4000");
        assertEquals("4000", env.getProperty("spring.datasource.hikari.connection-timeout"));
    }
}
