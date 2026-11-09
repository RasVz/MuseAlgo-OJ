package com.musealgo.oj.problem.application;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.problem.error.ProblemErrorCode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemCase;
import com.musealgo.oj.problem.entity.ProblemSample;
import com.musealgo.oj.problem.entity.dto.ProblemDTO;
import com.musealgo.oj.problem.entity.vo.ProblemCaseVO;
import com.musealgo.oj.problem.entity.vo.ProblemDetailVO;
import com.musealgo.oj.problem.format.ProblemFormatApplyRequest;
import com.musealgo.oj.problem.format.ProblemFormatPreviewItem;
import com.musealgo.oj.problem.format.ProblemFormatRequest;
import com.musealgo.oj.problem.format.ProblemFormatResult;
import com.musealgo.oj.problem.importer.FpsProblem;
import com.musealgo.oj.problem.importer.FpsTestCase;
import com.musealgo.oj.problem.infrastructure.ProblemSearchIndexer;
import com.musealgo.oj.problem.service.ProblemCaseService;
import com.musealgo.oj.problem.service.ProblemSampleService;
import com.musealgo.oj.problem.service.ProblemService;
import com.musealgo.oj.problem.service.TestCaseStorageService;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Application boundary for problem administration. Controllers must not update
 * problem rows or private test cases directly.
 * <p>
 * 2026-08-15 大服务类拆分：本题为门面，保留全部 public 方法签名（Controller/测试零改动），
 * 实现按域委托给：{@link ProblemCaseApplicationService}（用例）、{@link ProblemStdApplicationService}（std）、
 * {@link ProblemFormatApplicationService}（格式化）、{@link ProblemSampleApplicationService}（样例）、
 * {@link ProblemListQueryApplicationService}（列表查询）、{@link ProblemAccessGuard}（权限校验）。
 * 事务注解保留在门面方法上，语义与拆分前一致。
 * </p>
 */
@Service
public class ProblemManagementApplicationService {

    private final ProblemService problemService;
    private final ProblemReadAccessService readAccess;
    private final ProblemCaseService problemCaseService;
    private final ProblemSampleService problemSampleService;
    private final JdbcTemplate jdbcTemplate;
    private final TestCaseStorageService testCaseStorageService;
    private final ProblemSearchIndexer searchIndexer;
    private final ProblemAccessGuard accessGuard;
    private final ProblemCaseApplicationService caseApplicationService;
    private final ProblemSampleApplicationService sampleApplicationService;
    private final ProblemStdApplicationService stdApplicationService;
    private final ProblemFormatApplicationService formatApplicationService;
    private final ProblemListQueryApplicationService listQueryApplicationService;

    public ProblemManagementApplicationService(ProblemReadAccessService readAccess, ProblemService problemService,
                                               ProblemCaseService problemCaseService,
                                               ProblemSampleService problemSampleService,
                                               JdbcTemplate jdbcTemplate,
                                               TestCaseStorageService testCaseStorageService,
                                               ProblemSearchIndexer searchIndexer,
                                               ProblemAccessGuard accessGuard,
                                               ProblemCaseApplicationService caseApplicationService,
                                               ProblemSampleApplicationService sampleApplicationService,
                                               ProblemStdApplicationService stdApplicationService,
                                               ProblemFormatApplicationService formatApplicationService,
                                               ProblemListQueryApplicationService listQueryApplicationService) {
        this.readAccess = readAccess;
        this.problemService = problemService;
        this.problemCaseService = problemCaseService;
        this.problemSampleService = problemSampleService;
        this.jdbcTemplate = jdbcTemplate;
        this.testCaseStorageService = testCaseStorageService;
        this.searchIndexer = searchIndexer;
        this.accessGuard = accessGuard;
        this.caseApplicationService = caseApplicationService;
        this.sampleApplicationService = sampleApplicationService;
        this.stdApplicationService = stdApplicationService;
        this.formatApplicationService = formatApplicationService;
        this.listQueryApplicationService = listQueryApplicationService;
    }

    // ======================== 题目 CRUD ========================

    @Transactional(rollbackFor = Exception.class)
    public Long create(ProblemDTO command, Long actorId) {
        SecurityUtils.requireTeacherOrAdmin();

        Problem problem = new Problem();
        applyEditableFields(problem, command);
        problem.setAuthorId(actorId);
        if (!problemService.save(problem)) {
            throw new IllegalStateException("题目保存失败");
        }
        sampleApplicationService.replaceSamples(problem.getId(), command.getSamples());
        matchTags(problem);
        searchIndexer.indexProblem(problem);
        return problem.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public Long createFromFps(FpsProblem imported, Long actorId) {
        SecurityUtils.requireTeacherOrAdmin();

        Problem problem = new Problem();
        problem.setTitle(imported.title());
        problem.setContent(StatementSanitizer.sanitize(imported.description()));
        problem.setInputDesc(StatementSanitizer.sanitize(imported.inputDescription()));
        problem.setOutputDesc(StatementSanitizer.sanitize(imported.outputDescription()));
        problem.setHint(StatementSanitizer.sanitize(imported.hint()));
        problem.setTimeLimit(imported.timeLimitMillis());
        problem.setMemoryLimit(imported.memoryLimitMegabytes());
        problem.setDifficulty(800);
        problem.setTags("");
        problem.setSource(StringUtils.hasText(imported.source()) ? imported.source().trim() : "FPS 导入");
        problem.setIsPublic(false);
        problem.setAuthorId(actorId);
        if (!problemService.save(problem)) {
            throw new IllegalStateException("题目保存失败");
        }
        try {
            sampleApplicationService.replaceSamples(problem.getId(), imported.samples().stream()
                    .map(s -> {
                        ProblemSample row = new ProblemSample();
                        row.setInput(s.input());
                        row.setOutput(s.output());
                        return row;
                    })
                    .toList());
            caseApplicationService.saveImportedCases(problem.getId(), imported.testCases());
            caseApplicationService.updateTestCaseCount(problem.getId(), imported.testCases().size());
            matchTags(problem);
            searchIndexer.indexProblem(problem);
            return problem.getId();
        } catch (RuntimeException e) {
            testCaseStorageService.deleteByProblemId(problem.getId());
            throw e;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(ProblemDTO command) {
        if (command.getId() == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "题目ID不能为空");
        }
        Problem existing = accessGuard.requireManagedProblem(command.getId());
        applyEditableFields(existing, command);
        if (!problemService.updateById(existing)) {
            throw new IllegalStateException("题目更新失败");
        }
        if (command.getSamples() != null) {
            sampleApplicationService.replaceSamples(existing.getId(), command.getSamples());
        }
        matchTags(existing);
        searchIndexer.indexProblem(existing);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long problemId) {
        // 与用例写入、标准答案保存统一加锁顺序（先题目行，再动用例行），避免交叉加锁死锁
        caseApplicationService.lockProblemRow(problemId);
        accessGuard.requireManagedProblem(problemId);
        // 先删 DB 记录，防止判题 Worker 在删文件后读 DB 仍然拿到已失效的记录
        problemCaseService.remove(new LambdaQueryWrapper<ProblemCase>()
                .eq(ProblemCase::getProblemId, problemId));
        problemSampleService.remove(new LambdaQueryWrapper<ProblemSample>()
                .eq(ProblemSample::getProblemId, problemId));
        if (!problemService.removeById(problemId)) {
            throw new IllegalStateException("题目删除失败");
        }
        // DB 事务提交后再删文件：删除失败回滚时题目与用例记录仍然有效，文件不能提前消失
        testCaseStorageService.deleteProblemDirsAfterCommit(problemId);
        // 判题侧用例缓存同步失效，避免已删除题目的用例在 TTL 内仍被读取
        caseApplicationService.evictCasesAfterCommit(problemId);
        // 同步移除搜索索引（失败仅 WARN，不影响主流程）
        searchIndexer.deleteProblem(problemId);
    }

    // ======================== 用例域委托 ========================

    public List<ProblemCaseVO> getPrivateCases(Long problemId) {
        return caseApplicationService.getPrivateCases(problemId);
    }

    /** 用例集合指纹（非原子辅助判断）：保存前的最终一致性校验在 saveStdTested 内部完成。 */
    public String caseFingerprint(Long problemId) {
        return caseApplicationService.caseFingerprint(problemId);
    }    @Transactional(rollbackFor = Exception.class)
    public int importCases(Long problemId, MultipartFile file) {
        return caseApplicationService.importCases(problemId, file);
    }

    @Transactional(rollbackFor = Exception.class)
    public int saveCases(Long problemId, List<FpsTestCase> cases) {
        return caseApplicationService.saveCases(problemId, cases);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteCase(Long caseId) {
        caseApplicationService.deleteCase(caseId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void clearCases(Long problemId) {
        caseApplicationService.clearCases(problemId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProblemCaseApplicationService.AppendResult appendCases(Long problemId, List<FpsTestCase> cases) {
        return caseApplicationService.appendCases(problemId, cases);
    }

    // ======================== std 域委托 ========================

    @Transactional(rollbackFor = Exception.class)
    public void saveStd(Long problemId, String code, String explanation) {
        stdApplicationService.saveStd(problemId, code, explanation);
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveStdTested(Long problemId, String code, String explanation, String expectFingerprint) {
        stdApplicationService.saveStdTested(problemId, code, explanation, expectFingerprint);
    }

    public Map<String, Object> getStd(Long problemId) {
        return stdApplicationService.getStd(problemId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void clearStd(Long problemId) {
        stdApplicationService.clearStd(problemId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveStdAndClearCases(Long problemId, String code, String explanation) {
        stdApplicationService.saveStdAndClearCases(problemId, code, explanation);
    }

    // ======================== 格式化域委托 ========================

    public ProblemFormatResult formatProblems(ProblemFormatRequest request) {
        return formatApplicationService.formatProblems(request);
    }

    public ProblemFormatPreviewItem previewFormatProblem(Long problemId, boolean ipMode) {
        return formatApplicationService.previewFormatProblem(problemId, ipMode);
    }

    @Transactional(rollbackFor = Exception.class)
    public void applyFormattedProblem(ProblemFormatApplyRequest request) {
        formatApplicationService.applyFormattedProblem(request);
    }

    // ======================== 查询方法 ========================

    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<Map<String, Object>> getMyCreatedProblemList(
            Long userId, Integer page, Integer size, String keyword, Integer difficulty,
            String tag, Integer privacy, Boolean isPublic, String sortBy, String sortOrder) {
        return listQueryApplicationService.getMyCreatedProblemList(
                userId, page, size, keyword, difficulty, tag, privacy, isPublic, sortBy, sortOrder);
    }

    /** 管理员全量题库列表（不限创建人，含私有题） */
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<Map<String, Object>> adminList(
            Integer page, Integer size, String keyword, Integer difficulty,
            String tag, Integer privacy, Boolean isPublic, String sortBy, String sortOrder) {
        return listQueryApplicationService.adminList(
                page, size, keyword, difficulty, tag, privacy, isPublic, sortBy, sortOrder);
    }

    /** 题目详情：校验公开/归属/班级/比赛权限后返回样例，隐藏标准答案 */
    public ProblemDetailVO getDetail(Long problemId) {
        readAccess.requireReadable(problemId);
        Problem problem = problemService.getById(problemId);
        if (problem == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_NOT_FOUND, "题目不存在");
        }
        ProblemDetailVO vo = new ProblemDetailVO();
        org.springframework.beans.BeanUtils.copyProperties(problem, vo);
        // 标准答案仅教师可见：学生详情接口绝不返回
        vo.setStdAnswer(null);
        vo.setStdExplanation(null);
        vo.setSamples(sampleApplicationService.getSamples(problemId));
        return vo;
    }

    // ======================== 私有辅助 ========================

    private void matchTags(Problem problem) {
        if (problem.getTitle() == null) return;
        // 清理旧关联
        jdbcTemplate.update("DELETE FROM problem_tag WHERE problem_id = ?", problem.getId());
        // 从标题和 tags 提取关键词
        String keywords = (problem.getTitle() + " " +
                (problem.getTags() != null ? problem.getTags() : ""))
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\[\\]\"]", " ");
        // 查所有叶子标签
        List<Map<String, Object>> allTags = jdbcTemplate.queryForList(
                "SELECT id, name, code FROM tag WHERE parent_id IS NOT NULL");
        for (var row : allTags) {
            String name = (String) row.get("name");
            String code = (String) row.get("code");
            if ((name != null && keywords.contains(name)) ||
                (code != null && keywords.contains(code))) {
                jdbcTemplate.update(
                    "INSERT IGNORE INTO problem_tag (problem_id, tag_id, create_time) VALUES (?, ?, NOW())",
                    problem.getId(), row.get("id"));
            }
        }
    }

    private void applyEditableFields(Problem target, Problem source) {
        target.setTitle(source.getTitle());
        target.setContestId(source.getContestId());
        // 题面是教师可控的自由文本，学生端按 HTML 渲染：写入侧统一消毒，避免存储型 XSS
        target.setContent(StatementSanitizer.sanitize(source.getContent()));
        target.setInputDesc(StatementSanitizer.sanitize(source.getInputDesc()));
        target.setOutputDesc(StatementSanitizer.sanitize(source.getOutputDesc()));
        target.setHint(StatementSanitizer.sanitize(source.getHint()));
        target.setTimeLimit(source.getTimeLimit());
        target.setMemoryLimit(source.getMemoryLimit());
        target.setDifficulty(source.getDifficulty());
        target.setTags(source.getTags());
        target.setSource(source.getSource());
        target.setIsPublic(source.getIsPublic());
    }
}
