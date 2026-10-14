package top.everblue.oj.ai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.ai.entity.PromptTemplate;
import top.everblue.oj.ai.gateway.PromptRegistry;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.annotation.AuditLog;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 提示词模板管理（E2）：查看版本、新建版本、切换 active。 */
@Tag(name = "提示词模板", description = "模板版本管理与激活切换")
@RestController
@RequestMapping("/admin/ai/prompts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminAiPromptController {

    private final PromptRegistry promptRegistry;

    @Operation(summary = "模板版本列表")
    @GetMapping("/list")
    public Result<List<PromptTemplate>> list(@RequestParam(required = false) String key) {
        return Result.success(promptRegistry.listDbVersions(key));
    }

    @Operation(summary = "新建版本（自动激活）")
    @AuditLog(module = "ai", action = "create_prompt")
    @PostMapping("/create")
    public Result<PromptTemplate> create(@RequestBody Map<String, String> body) {
        String key = body.get("key");
        String content = body.get("content");
        if (key == null || key.isBlank() || content == null || content.isBlank()) {
            return Result.error("key 与 content 不能为空");
        }
        return Result.success("新版本已创建并激活", promptRegistry.createVersion(key.trim(), content));
    }

    @Operation(summary = "激活指定版本")
    @AuditLog(module = "ai", action = "activate_prompt")
    @PostMapping("/{id}/activate")
    public Result<String> activate(@PathVariable Long id) {
        promptRegistry.activateVersion(id);
        return Result.success("已激活");
    }
}
