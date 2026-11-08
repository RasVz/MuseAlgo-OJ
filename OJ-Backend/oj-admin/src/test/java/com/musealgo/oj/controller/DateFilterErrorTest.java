package com.musealgo.oj.controller;

import com.musealgo.oj.ai.controller.AiAdminController;
import com.musealgo.oj.ai.query.AiUsageQueryService;
import com.musealgo.oj.common.exception.GlobalExceptionHandler;
import com.musealgo.oj.system.service.AuditLogService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class DateFilterErrorTest {
    @ParameterizedTest
    @ValueSource(strings={"/admin/audit-logs/list","/admin/ai/logs"})
    void malformedStartDateIsAnInputErrorWithoutEchoingIt(String path) throws Exception {
        var audits=mock(AuditLogService.class);var usage=mock(AiUsageQueryService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new AdminAuditLogController(audits),
                new AiAdminController(usage,null,null,null,null,null)).setControllerAdvice(new GlobalExceptionHandler()).build();
        var response=mvc.perform(get(path).param("start","synthetic-invalid-date")).andReturn().getResponse();
        String body=response.getContentAsString();
        assertEquals(200,response.getStatus());
        assertTrue(body.contains("\"code\":400"),body);
        assertTrue(body.contains("PARAMETER_TYPE_MISMATCH"),body);
        assertFalse(body.contains("synthetic-invalid-date"));
        verifyNoInteractions(audits,usage);
    }
}
