package com.musealgo.oj.ai.draft;

import com.musealgo.oj.ai.draft.dto.DraftCreateRequest;
import com.musealgo.oj.ai.draft.dto.DraftPromptPreviewRequest;
import com.musealgo.oj.ai.draft.dto.DraftRewriteRequest;
import com.musealgo.oj.ai.draft.dto.DraftSaveRequest;
import com.musealgo.oj.ai.draft.vo.DraftDetailVO;
import com.musealgo.oj.ai.draft.vo.DraftPageVO;
import com.musealgo.oj.ai.draft.vo.DraftPromptVO;
import com.musealgo.oj.ai.draft.vo.DraftSaveResultVO;
import com.musealgo.oj.ai.draft.vo.DraftVO;
import com.musealgo.oj.api.problem.SimilarProblemView;
import com.musealgo.oj.common.annotation.RateLimit;
import com.musealgo.oj.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题面生成草稿（教师端命题工作台的第一步）。
 * <p>
 * 所有接口都只做「受理 + 查询 + 短操作」：真正的模型调用在服务端 worker 里跑，
 * 因此这里的响应时间与模型快慢无关，也不会因为教师关掉页面而丢结果。
 * </p>
 */
@Tag(name = "教师 AI 命题草稿", description = "AI 题面生成、审阅、定向重写与落库")
@RestController
@RequestMapping("/ai/teacher/drafts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
public class ProblemDraftController {

    private final ProblemDraftService draftService;

    @Operation(summary = "受理题面生成（1~3 个方案）")
    @PostMapping
    @RateLimit(key = "problemDraft", action = "题面生成", time = 60, count = 6)
    public Result<List<DraftVO>> create(@RequestBody(required = false) DraftCreateRequest request) {
        return Result.success(draftService.create(request));
    }

    @Operation(summary = "生成历史；传 groupId 时返回该组全部方案")
    @GetMapping
    public Result<DraftPageVO> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String groupId) {
        return Result.success(draftService.list(page, size, status, groupId));
    }

    @Operation(summary = "草稿详情")
    @GetMapping("/{id}")
    public Result<DraftDetailVO> detail(@PathVariable Long id) {
        return Result.success(draftService.detail(id));
    }

    @Operation(summary = "取消排队中的生成")
    @PostMapping("/{id}/cancel")
    public Result<DraftVO> cancel(@PathVariable Long id) {
        return Result.success(draftService.cancel(id));
    }

    @Operation(summary = "重试失败的生成")
    @PostMapping("/{id}/retry")
    public Result<DraftVO> retry(@PathVariable Long id) {
        return Result.success(draftService.retry(id));
    }

    @Operation(summary = "删除草稿（生成中也可删除，模型调用不会中止）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        draftService.delete(id);
        return Result.success(null);
    }

    @Operation(summary = "保存人工修改并重跑质量检查")
    @PostMapping("/{id}/content")
    public Result<DraftDetailVO> updateContent(@PathVariable Long id, @RequestBody DraftContent content) {
        return Result.success(draftService.updateContent(id, content));
    }

    @Operation(summary = "定向重写单个字段（产出子草稿）")
    @PostMapping("/{id}/rewrite")
    @RateLimit(key = "problemDraft", action = "题面生成", time = 60, count = 6)
    public Result<DraftDetailVO> rewrite(@PathVariable Long id,
                                         @RequestBody(required = false) DraftRewriteRequest request) {
        return Result.success(draftService.rewrite(id, request));
    }

    @Operation(summary = "保存为私有题目")
    @PostMapping("/{id}/save")
    public Result<DraftSaveResultVO> save(@PathVariable Long id,
                                          @RequestBody(required = false) DraftSaveRequest request) {
        return Result.success(draftService.save(id, request));
    }

    @Operation(summary = "提示词预览（不调用模型）")
    @PostMapping("/prompt-preview")
    public Result<DraftPromptVO> promptPreview(@RequestBody(required = false) DraftPromptPreviewRequest request) {
        return Result.success(draftService.promptPreview(request == null ? null : request.brief()));
    }

    @Operation(summary = "标题相近的历史题目")
    @GetMapping("/{id}/similar")
    public Result<List<SimilarProblemView>> similar(@PathVariable Long id) {
        return Result.success(draftService.similar(id));
    }
}
