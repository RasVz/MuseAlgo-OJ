package top.everblue.oj.common.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.everblue.oj.system.entity.AuditLog;
import top.everblue.oj.system.service.AuditLogService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogAspectTest {

    @Test
    void auditedParametersMaskNestedSecrets() throws Throwable {
        AuditLogService auditLogService = mock(AuditLogService.class);
        AuditLogAspect aspect = new AuditLogAspect();
        ReflectionTestUtils.setField(aspect, "auditLogService", auditLogService);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        top.everblue.oj.common.annotation.AuditLog annotation =
                mock(top.everblue.oj.common.annotation.AuditLog.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringTypeName()).thenReturn("UserController");
        when(signature.getName()).thenReturn("changePassword");
        when(joinPoint.getArgs()).thenReturn(new Object[]{Map.of(
                "oldPassword", "old-secret",
                "profile", Map.of("newPassword", "new-secret", "nickname", "alice"))});
        when(joinPoint.proceed()).thenReturn("ok");
        when(annotation.module()).thenReturn("user");
        when(annotation.action()).thenReturn("change_password");

        aspect.around(joinPoint, annotation);

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).asyncInsert(captor.capture());
        String params = captor.getValue().getRequestParams();
        assertFalse(params.contains("old-secret"));
        assertFalse(params.contains("new-secret"));
        assertTrue(params.contains("alice"));
        assertTrue(params.contains("******"));
    }
}
