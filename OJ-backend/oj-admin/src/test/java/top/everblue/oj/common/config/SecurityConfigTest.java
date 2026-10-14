package top.everblue.oj.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import top.everblue.oj.common.filter.JwtAuthenticationTokenFilter;
import top.everblue.oj.common.utils.JwtUtils;
import top.everblue.oj.system.service.JwtBlacklistService;
import top.everblue.oj.system.service.impl.UserDetailsServiceImpl;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import({ SecurityConfig.class, JwtAuthenticationTokenFilter.class, SecurityProbeController.class })
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
                .andExpect(status().isForbidden());
    }
}
