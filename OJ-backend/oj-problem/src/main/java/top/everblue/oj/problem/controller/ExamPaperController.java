package top.everblue.oj.problem.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.annotation.AuditLog;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.problem.entity.ExamPaper;
import top.everblue.oj.problem.entity.dto.ExamAnswerSubmitDTO;
import top.everblue.oj.problem.entity.dto.ExamPaperDTO;
import top.everblue.oj.problem.entity.vo.ExamPaperVO;
import top.everblue.oj.problem.entity.vo.ExamAttemptVO;
import top.everblue.oj.problem.entity.vo.ExamPaperStatsVO;
import top.everblue.oj.problem.service.ExamPaperService;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import top.everblue.oj.common.exception.ForbiddenException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 训练试卷（训练 tab）：GESP/CSP 真题套卷等，独立于题单/课程/题库。
 */
@Tag(name = "训练试卷", description = "训练 tab 的试卷库与试卷管理")
@RestController
@RequiredArgsConstructor
@RequestMapping("/exam-paper")
public class ExamPaperController {

    private final ExamPaperService examPaperService;

    @Operation(summary = "试卷列表")
    @GetMapping("/list")
    public Result<Page<ExamPaper>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword
    ) {
        // 普通用户只看已发布；管理员可看全部（含草稿）
        boolean publishedOnly = !SecurityUtils.isAdmin();
        return Result.success(examPaperService.listPapers(page, size, category, keyword, publishedOnly));
    }

    @Operation(summary = "试卷详情")
    @GetMapping("/{id}")
    public Result<ExamPaperVO> detail(@PathVariable Long id) {
        // 标准答案仅管理员可见（编辑回填）；学生作答后由判分结果回显
        boolean admin = SecurityUtils.isAdmin();
        ExamPaperVO paper = examPaperService.detail(id, admin);
        if (paper != null && !admin && !Integer.valueOf(1).equals(paper.getIsPublished())) {
            return Result.error(403, "试卷尚未发布");
        }
        return Result.success(paper);
    }

    @Operation(summary = "创建/更新试卷（管理员）")
    @AuditLog(module = "exam_paper", action = "save_exam_paper")
    @PostMapping("/save")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> save(@Valid @RequestBody ExamPaperDTO dto) {
        return Result.success("保存成功", examPaperService.savePaper(dto, SecurityUtils.getUserId()));
    }

    @Operation(summary = "删除试卷（管理员）")
    @AuditLog(module = "exam_paper", action = "delete_exam_paper")
    @PostMapping("/delete/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<String> delete(@PathVariable Long id) {
        examPaperService.deletePaper(id);
        return Result.success("删除成功");
    }

    @Operation(summary = "提交客观题作答并即时判分")
    @PostMapping("/{id}/submit-answers")
    public Result<List<ExamPaperVO.ExamQuestionResultVO>> submitAnswers(
            @PathVariable Long id, @Valid @RequestBody ExamAnswerSubmitDTO submit) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new ForbiddenException("请先登录");
        }
        return Result.success(examPaperService.submitAnswers(id, userId, submit));
    }

    @Operation(summary = "开始限时作答")
    @PostMapping("/{id}/attempt/start")
    public Result<ExamAttemptVO> startAttempt(@PathVariable Long id) {
        return Result.success(examPaperService.startAttempt(id, requireUserId()));
    }

    @Operation(summary = "查询当前作答会话（含剩余时间）")
    @GetMapping("/{id}/attempt")
    public Result<ExamAttemptVO> getAttempt(@PathVariable Long id) {
        return Result.success(examPaperService.getAttempt(id, requireUserId()));
    }

    @Operation(summary = "手动交卷")
    @PostMapping("/{id}/attempt/finish")
    public Result<ExamAttemptVO> finishAttempt(@PathVariable Long id) {
        return Result.success(examPaperService.finishAttempt(id, requireUserId()));
    }

    private Long requireUserId() {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new ForbiddenException("请先登录");
        }
        return userId;
    }

    @Operation(summary = "复制试卷（管理员）")
    @AuditLog(module = "exam_paper", action = "copy_exam_paper")
    @PostMapping("/{id}/copy")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> copy(@PathVariable Long id) {
        return Result.success("复制成功", examPaperService.copyPaper(id, SecurityUtils.getUserId()));
    }

    @Operation(summary = "试卷成绩统计（管理员）")
    @GetMapping("/{id}/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<ExamPaperStatsVO> stats(@PathVariable Long id) {
        return Result.success(examPaperService.paperStats(id));
    }

    @Operation(summary = "成绩明细（管理员）")
    @GetMapping("/{id}/attempts")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Page<ExamPaperVO.ExamAttemptRowVO>> attempts(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return Result.success(examPaperService.listAttempts(id, page, size));
    }

    @Operation(summary = "成绩明细 CSV 导出（管理员）")
    @GetMapping("/{id}/attempts/export")
    @PreAuthorize("hasRole('ADMIN')")
    public void exportAttempts(@PathVariable Long id, HttpServletResponse response) throws IOException {
        String csv = examPaperService.buildAttemptsCsv(id);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=exam-paper-" + id + "-attempts.csv");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}; // UTF-8 BOM：Excel 识别中文
        OutputStream out = response.getOutputStream();
        out.write(bom);
        out.write(csv.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
