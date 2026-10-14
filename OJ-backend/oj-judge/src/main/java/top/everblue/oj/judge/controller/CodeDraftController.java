package top.everblue.oj.judge.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.judge.entity.UserCodeDraft;
import top.everblue.oj.judge.service.UserCodeDraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 代码草稿的保存与恢复接口。
 * <p>
 * 草稿<strong>按 (用户, 题目, 语言) 三元组唯一</strong>，而不是按题目唯一：
 * 同一道题学生可能会在 C++ 与 Python 之间来回切换，用一道题一份草稿必然互相覆盖。
 * 切换语言时前端靠这个维度各取各的草稿，因此这三个字段一个都不能省。
 * </p>
 * <p>
 * 保存接口是<strong>被前端防抖自动调用</strong>的（打字停止后才发），所以写入必须是幂等的
 * upsert 而不是 insert，且不返回任何需要前端判断分支的信息；反过来，
 * 前端也因此不需要为「保存失败」做重试之外的补偿——真丢了最坏结果是回到模板代码。
 * </p>
 * <p>
 * 用户身份一律从 SecurityContext 取（{@link SecurityUtils#getUserId()}），
 * <strong>不接收前端传入的 userId</strong>：草稿是私有数据，信任入参等于任意用户可以覆盖别人的代码。
 * 草稿未命中时返回 {@code null} 而非抛 404，前端据此填充模板代码即可。
 * </p>
 */
@Tag(name = "代码草稿", description = "代码草稿的保存与恢复")
@RestController
@RequiredArgsConstructor
@RequestMapping("/problem/draft")
public class CodeDraftController {

    private final UserCodeDraftService draftService;

    /**
     * 获取当前用户某道题的对应语言的草稿
     */
    @Operation(summary = "获取代码草稿")
    @GetMapping("/get")
    public Result<UserCodeDraft> getDraft(@RequestParam Long problemId, @RequestParam String language) {
        Long userId = SecurityUtils.getUserId();
        UserCodeDraft draft = draftService.getDraft(userId, problemId, language);
        return Result.success(draft);
    }

    /**
     * 保存代码草稿 (防抖自动调用)
     */
    @Operation(summary = "保存代码草稿")
    @PostMapping("/save")
    public Result<String> saveDraft(@RequestBody UserCodeDraft draftReq) {
        Long userId = SecurityUtils.getUserId();

        // 参数校验
        if (draftReq.getProblemId() == null || draftReq.getLanguage() == null) {
            return Result.error("参数不完整");
        }

        boolean success = draftService.saveOrUpdateDraft(userId, draftReq.getProblemId(), draftReq.getLanguage(), draftReq.getCode());

        if (success) {
            return Result.success("保存成功");
        } else {
            return Result.error("保存失败");
        }
    }
}