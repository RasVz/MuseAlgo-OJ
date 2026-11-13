package com.musealgo.oj.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.musealgo.oj.dto.AdminClassSummary;
import com.musealgo.oj.service.AdminClassDirectoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminClassControllerSecurityTest {

    private AnnotationConfigApplicationContext context;
    private AdminClassController controller;
    private AdminClassDirectoryService directoryService;

    @BeforeEach
    void startMethodSecuredContext() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        controller = context.getBean(AdminClassController.class);
        directoryService = context.getBean(AdminClassDirectoryService.class);
    }

    @AfterEach
    void closeContextAndClearAuthentication() {
        SecurityContextHolder.clearContext();
        if (context != null) {
            context.close();
        }
    }

    @Test
    void administratorCanReadTheDirectory() {
        authenticate("ROLE_ADMIN");
        var page = new Page<AdminClassSummary>(1, 20, 0);
        when(directoryService.list(1, 20, null, "all", "newest")).thenReturn(page);

        var result = controller.list(1, 20, null, "all", "newest");

        assertEquals(page, result.getData());
        verify(directoryService).list(1, 20, null, "all", "newest");
    }

    @Test
    void teacherAndStudentCannotReadTheDirectory() {
        for (String role : List.of("ROLE_TEACHER", "ROLE_STUDENT")) {
            authenticate(role);
            assertThrows(AccessDeniedException.class,
                    () -> controller.list(1, 20, null, "all", "newest"));
            SecurityContextHolder.clearContext();
        }
        verifyNoInteractions(directoryService);
    }

    @Test
    void anonymousCallerMustAuthenticate() {
        SecurityContextHolder.clearContext();

        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> controller.list(1, 20, null, "all", "newest"));

        verifyNoInteractions(directoryService);
    }

    private static void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("directory-test", "ignored",
                        List.of(new SimpleGrantedAuthority(role))));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(proxyTargetClass = true)
    static class TestConfiguration {
        @Bean
        AdminClassDirectoryService directoryService() {
            return mock(AdminClassDirectoryService.class);
        }

        @Bean
        AdminClassController adminClassController(AdminClassDirectoryService directoryService) {
            return new AdminClassController(directoryService);
        }
    }
}
