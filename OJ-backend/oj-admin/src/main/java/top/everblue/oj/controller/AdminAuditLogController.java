package top.everblue.oj.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.system.entity.AuditLog;
import top.everblue.oj.system.service.AuditLogService;

import java.time.LocalDate;
import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 审计日志查询（管理员）。 */
@Tag(name = "审计日志", description = "操作审计日志查询")
@RestController
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditLogController {

    private final AuditLogService auditLogService;

    @Operation(summary = "审计日志列表")
    @GetMapping("/list")
    public Result<Page<AuditLog>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(userId != null, AuditLog::getUserId, userId)
                .eq(StringUtils.hasText(module), AuditLog::getModule, module)
                .like(StringUtils.hasText(action), AuditLog::getAction, action)
                .ge(StringUtils.hasText(start), AuditLog::getCreateTime, parse(start))
                .le(StringUtils.hasText(end), AuditLog::getCreateTime, parse(end))
                .orderByDesc(AuditLog::getCreateTime);
        return Result.success(auditLogService.selectPage(new Page<>(page, size), wrapper));
    }

    private static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null; // 条件为 false 时 MyBatis-Plus 也会立即求值参数，必须容忍空值
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return LocalDate.parse(value).atStartOfDay();
        }
    }
}
