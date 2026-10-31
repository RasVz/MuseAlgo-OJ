package com.musealgo.oj.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import com.musealgo.oj.common.exception.GlobalExceptionHandler;
import com.musealgo.oj.common.filter.JwtAuthenticationTokenFilter;
import com.musealgo.oj.common.utils.JwtUtils;
import com.musealgo.oj.system.service.JwtBlacklistService;
import com.musealgo.oj.system.service.impl.UserDetailsServiceImpl;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(properties="oj.security.api-docs-public=false")
@Import({ SecurityConfig.class, JwtAuthenticationTokenFilter.class, SecurityProbeController.class,
        SecurityAuthorizationExceptionHandler.class, GlobalExceptionHandler.class })
class SecurityConfigTest {

    @SpringBootConfiguration
    static class TestConfiguration {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private JwtBlacklistService jwtBlacklistService;

    @Test
    void expiredOrInvalidTokenRequestToProtectedEndpointReturns401() throws Exception {
        mockMvc.perform(get("/api/probe/authenticated")
                        .header("Authorization", "Bearer expired-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void authenticatedUserWithoutRoleStillGets403() throws Exception {
        mockMvc.perform(get("/api/probe/admin")
                        .with(user("student").roles("STUDENT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }
    @Test
    void productionDocsAndMetricsRejectAnonymousAndStudents() throws Exception {
        mockMvc.perform(get("/doc.html")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus").with(user("student").roles("STUDENT")))
                .andExpect(status().isForbidden());
    }

    /**
     * 编排层探针必须匿名可读，否则容器 healthcheck 拿到 401 就无法判定就绪，
     * 报告 worker 的受控退出恢复闭环失效（U-15）。
     */
    @Test
    void orchestratorProbesAreReadableAnonymously() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    /**
     * 放开探针不得顺带放开其余 actuator 端点：prometheus/metrics 仍然匿名 401、学生 403。
     */
    @Test
    void openingProbesDoesNotOpenOtherActuatorEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics").with(user("student").roles("STUDENT")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/env").with(user("student").roles("STUDENT")))
                .andExpect(status().isForbidden());
    }
}
