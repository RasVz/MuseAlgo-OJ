package com.musealgo.oj.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.musealgo.oj.common.exception.GlobalExceptionHandler;
import com.musealgo.oj.dto.AdminClassMemberCount;
import com.musealgo.oj.dto.AdminClassSummary;
import com.musealgo.oj.mapper.AdminClassDirectoryMapper;
import com.musealgo.oj.service.AdminClassDirectoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminClassControllerHttpContractTest {

    private AnnotationConfigApplicationContext context;
    private MockMvc mockMvc;
    private AdminClassDirectoryMapper mapper;

    @BeforeEach
    void startControllerAndSetAdminAuthentication() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        mapper = context.getBean(AdminClassDirectoryMapper.class);
        var controller = context.getBean(AdminClassController.class);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("directory-http-test", "ignored",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void closeContextAndClearAuthentication() {
        SecurityContextHolder.clearContext();
        if (context != null) {
            context.close();
        }
    }

    @Test
    void defaultQueryKeepsPageJsonAndFormatsTheRowTimestamp() throws Exception {
        var row = new AdminClassSummary();
        row.setId(7L);
        row.setName("Math 7");
        row.setDescription("Course intro");
        row.setSchool("School");
        row.setTeacherId(9L);
        row.setTeacherName("Teacher");
        row.setStudentCount(2L);
        row.setCreateTime(LocalDateTime.of(2026, 11, 13, 9, 5, 6));
        var page = new Page<AdminClassSummary>(1, 20, 1);
        page.setRecords(List.of(row));
        var count = new AdminClassMemberCount();
        count.setClassId(7L);
        count.setStudentCount(2L);
        when(mapper.selectPage(any(), isNull(), isNull(), eq("all"), eq("newest"))).thenReturn(page);
        when(mapper.selectStudentCounts(eq(List.of(7L)))).thenReturn(List.of(count));

        mockMvc.perform(get("/admin/classes/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(7))
                .andExpect(jsonPath("$.data.records[0].studentCount").value(2))
                .andExpect(jsonPath("$.data.records[0].createTime").value("2026-11-13 09:05:06"));
        verify(mapper).selectPage(any(), isNull(), isNull(), eq("all"), eq("newest"));
    }

    @Test
    void bindsSearchMemberFilterAndSortAndTrimsTheKeyword() throws Exception {
        when(mapper.selectPage(any(), eq("Teacher"), isNull(), eq("hasMembers"), eq("name")))
                .thenReturn(new Page<>(3, 200));

        mockMvc.perform(get("/admin/classes/list")
                        .param("page", "3")
                        .param("size", "200")
                        .param("keyword", "  Teacher  ")
                        .param("memberStatus", "hasMembers")
                        .param("sort", "name"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(3))
                .andExpect(jsonPath("$.data.size").value(200));

        verify(mapper).selectPage(any(), eq("Teacher"), isNull(), eq("hasMembers"), eq("name"));
    }

    @Test
    void rejectsInvalidSizeMemberFilterAndSortWithClientErrorCode() throws Exception {
        mockMvc.perform(get("/admin/classes/list").param("size", "201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
        mockMvc.perform(get("/admin/classes/list").param("memberStatus", "deleted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
        mockMvc.perform(get("/admin/classes/list").param("sort", "studentCount DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(proxyTargetClass = true)
    static class TestConfiguration {
        @Bean
        AdminClassDirectoryMapper mapper() {
            return mock(AdminClassDirectoryMapper.class);
        }

        @Bean
        AdminClassDirectoryService directoryService(AdminClassDirectoryMapper mapper) {
            return new AdminClassDirectoryService(mapper);
        }

        @Bean
        AdminClassController controller(AdminClassDirectoryService directoryService) {
            return new AdminClassController(directoryService);
        }
    }
}
