package com.musealgo.oj.ai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.musealgo.oj.ai.adminai.AiAdminException;
import com.musealgo.oj.ai.adminai.AiServiceAdminDtos;
import com.musealgo.oj.ai.application.AiServiceCatalogService;
import com.musealgo.oj.ai.application.AiServiceTestService;
import com.musealgo.oj.ai.application.AiServiceUpdateService;
import com.musealgo.oj.common.annotation.AuditLog;
import com.musealgo.oj.common.annotation.RateLimit;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.exception.ErrorResponses;
import com.musealgo.oj.common.utils.SecurityUtils;

import java.util.List;

/** 管理中心 AI 服务配置；与原 /admin/ai 用量接口共用管理员权限边界。 */
@Tag(name = "AI 服务管理", description = "管理员维护 AI 服务、连接测试与运行时切换")
@RestController
@RequestMapping("/admin/ai")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminAiServiceController {

    private final AiServiceCatalogService catalogService;
    private final AiServiceTestService testService;
    private final AiServiceUpdateService updateService;

    @Operation(summary = "AI 服务列表（不含密钥）")
    @GetMapping("/services")
    public Result<List<AiServiceAdminDtos.ServiceVo>> services() {
        return Result.success(catalogService.list());
    }

    public record AddressRequest(@jakarta.validation.constraints.NotBlank
                                 @jakarta.validation.constraints.Size(max = 512) String apiUrl,
                                 @jakarta.validation.constraints.Pattern(regexp = "AUTO|BASE_URL|FULL_URL") String addressType) { }

    @Operation(summary = "检查地址解析与安全策略，不调用模型")
    @AuditLog(module = "ai", action = "diagnose_address")
    @RateLimit(key = "ai-service-diagnose", time = 60, count = 10)
    @PostMapping("/services/diagnose")
    public Result<AiServiceCatalogService.AddressDiagnosis> diagnose(@Valid @RequestBody AddressRequest request) {
        return Result.success(catalogService.diagnose(request.apiUrl(), request.addressType()));
    }

    @Operation(summary = "新增 AI 服务")
    @AuditLog(module = "ai", action = "create_service")
    @PostMapping("/services")
    public Result<AiServiceAdminDtos.ServiceVo> create(
            @Valid @RequestBody AiServiceAdminDtos.CreateRequest request) {
        return Result.success(catalogService.create(request, SecurityUtils.getUserId()));
    }

    @Operation(summary = "编辑 AI 服务；当前服务使用测试并保存应用")
    @AuditLog(module = "ai", action = "update_service")
    @RateLimit(key = "ai-service-update", time = 60, count = 10)
    @PutMapping("/services/{id}")
    public Result<AiServiceAdminDtos.UpdateResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody AiServiceAdminDtos.UpdateRequest request) {
        return Result.success(updateService.update(id, request, SecurityUtils.getUserId()));
    }

    @Operation(summary = "测试已保存版本；结果绑定配置版本")
    @AuditLog(module = "ai", action = "test_service")
    @RateLimit(key = "ai-service-test", time = 60, count = 5)
    @PostMapping("/services/{id}/test")
    public Result<AiServiceAdminDtos.TestReport> test(
            @PathVariable Long id,
            @Valid @RequestBody AiServiceAdminDtos.VersionRequest request) {
        return Result.success(testService.test(id, request.version(), SecurityUtils.getUserId()));
    }

    @Operation(summary = "原子设为当前服务；要求同版本测试通过")
    @AuditLog(module = "ai", action = "activate_service")
    @PostMapping("/services/{id}/activate")
    public Result<AiServiceAdminDtos.RuntimeVo> activate(
            @PathVariable Long id,
            @Valid @RequestBody AiServiceAdminDtos.ActivateRequest request) {
        return Result.success(updateService.activate(
                id, request.version(), request.runtimeVersion(), SecurityUtils.getUserId()));
    }

    @Operation(summary = "启用备选服务，不自动选用")
    @AuditLog(module = "ai", action = "enable_service")
    @PostMapping("/services/{id}/enable")
    public Result<AiServiceAdminDtos.ServiceVo> enable(
            @PathVariable Long id,
            @Valid @RequestBody AiServiceAdminDtos.VersionRequest request) {
        return Result.success(catalogService.changeEnabled(
                id, request.version(), true, SecurityUtils.getUserId()));
    }

    @Operation(summary = "停用备选服务")
    @AuditLog(module = "ai", action = "disable_service")
    @PostMapping("/services/{id}/disable")
    public Result<AiServiceAdminDtos.ServiceVo> disable(
            @PathVariable Long id,
            @Valid @RequestBody AiServiceAdminDtos.VersionRequest request) {
        return Result.success(catalogService.changeEnabled(
                id, request.version(), false, SecurityUtils.getUserId()));
    }

    @Operation(summary = "软删除非当前服务")
    @AuditLog(module = "ai", action = "delete_service")
    @DeleteMapping("/services/{id}")
    public Result<String> delete(
            @PathVariable Long id,
            @Valid @RequestBody AiServiceAdminDtos.VersionRequest request) {
        catalogService.delete(id, request.version(), SecurityUtils.getUserId());
        return Result.success("服务已删除");
    }

    @Operation(summary = "读取 AI 运行模式与当前服务摘要")
    @GetMapping("/runtime")
    public Result<AiServiceAdminDtos.RuntimeVo> runtime() {
        return Result.success(catalogService.runtimeView());
    }

    @Operation(summary = "明确停用 AI 或恢复旧环境配置")
    @AuditLog(module = "ai", action = "update_runtime")
    @PutMapping("/runtime")
    public Result<AiServiceAdminDtos.RuntimeVo> updateRuntime(
            @Valid @RequestBody AiServiceAdminDtos.RuntimeUpdateRequest request) {
        return Result.success(catalogService.updateRuntime(
                request.mode(), request.version(), SecurityUtils.getUserId()));
    }

    @ExceptionHandler(AiAdminException.class)
    public Result<String> handleAdminException(AiAdminException e) {
        return ErrorResponses.failure(e.getErrorCode(), e.getMessage());
    }
}
