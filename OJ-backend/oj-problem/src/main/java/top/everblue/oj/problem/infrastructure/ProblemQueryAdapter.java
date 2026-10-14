package top.everblue.oj.problem.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.problem.ProblemDigest;
import top.everblue.oj.api.problem.ProblemQuery;
import top.everblue.oj.api.problem.TestCaseView;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.entity.ProblemCase;
import top.everblue.oj.problem.mapper.ProblemCaseMapper;
import top.everblue.oj.problem.mapper.ProblemMapper;
import top.everblue.oj.problem.service.TestCaseStorageService;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 判题侧读取题目的适配层：实现跨模块的 {@link ProblemQuery} SPI。
 * <p>
 * 存在意义是<b>依赖倒置</b>：判题模块只认 SPI 接口，不反向依赖题目模块的实体与 Mapper，
 * 因此题目表结构调整不会波及判题链路。
 * </p>
 * <p>
 * 这里的返回值有两个容易踩的单位约定，改动前务必确认：
 * </p>
 * <ul>
 *   <li>{@link #findProblemLimits(Long)} 的时间单位是<b>毫秒</b>，内存单位是 <b>KB</b>
 *       （已由存储的 MB 乘 1024 换算），不是 MB。</li>
 *   <li>题目不存在与字段为空<b>都走默认值而非抛异常</b>：判题 Worker 拿到的是
 *       一次提交的上下文，此时抛异常会让整批任务失败，而兜底跑出结果更有价值。</li>
 * </ul>
 */
@Service
public class ProblemQueryAdapter implements ProblemQuery {

    private final ProblemMapper problemMapper;
    private final ProblemCaseMapper problemCaseMapper;
    private final TestCaseStorageService testCaseStorage;

    /**
     * 判题用例缓存（高并发方案 A4）：
     * key=problemId → 全量用例（含 input/output 文本，DB 查询 + 用例文件读取的产物）。
     * <p>
     * 设计取舍：用例变更入口分散（FPS 导入 / 管理端编辑 / 文件直写），显式失效容易遗漏，
     * 故采用短 TTL 自动过期（默认 60s）。判题热点下同一题目的连续提交命中率接近 100%；
     * 用例变更最多延迟 60s 生效，教学场景可接受。
     * </p>
     */
    private final Cache<Long, List<TestCaseView>> testCaseCache;

    /** 题目资源限制缓存（timeLimit/memoryLimit 几乎不变） */
    private final Cache<Long, int[]> problemLimitsCache;

    public ProblemQueryAdapter(ProblemMapper problemMapper,
                               ProblemCaseMapper problemCaseMapper,
                               TestCaseStorageService testCaseStorage,
                               @Value("${oj.problem.testcase-cache-ttl-seconds:60}") long ttlSeconds,
                               @Value("${oj.problem.testcase-cache-max-size:100}") long maxSize) {
        this.problemMapper = problemMapper;
        this.problemCaseMapper = problemCaseMapper;
        this.testCaseStorage = testCaseStorage;
        this.testCaseCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maxSize)
                .build();
        this.problemLimitsCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maxSize)
                .build();
    }

    @Override
    public Optional<String> findTitleById(Long problemId) {
        if (problemId == null) return Optional.empty();
        Problem problem = problemMapper.selectById(problemId);
        return Optional.ofNullable(problem).map(Problem::getTitle);
    }

    @Override
    public Map<Long, String> findTitleBatch(Collection<Long> problemIds) {
        if (problemIds == null || problemIds.isEmpty()) return Collections.emptyMap();
        List<Problem> problems = problemMapper.selectBatchIds(problemIds);
        return problems.stream()
                .collect(Collectors.toMap(Problem::getId, Problem::getTitle));
    }

    @Override
    public List<ProblemDigest> findDigestBatch(Collection<Long> problemIds) {
        if (problemIds == null || problemIds.isEmpty()) return Collections.emptyList();
        List<Problem> problems = problemMapper.selectBatchIds(problemIds);
        return problems.stream()
                .map(p -> new ProblemDigest(
                        p.getId(),
                        p.getTitle(),
                        p.getDifficulty() == null ? 0 : p.getDifficulty().intValue(),
                        p.getIsPublic()))
                .toList();
    }

    @Override
    public int[] findProblemLimits(Long problemId) {
        if (problemId == null) return new int[]{1000, 131072};
        int[] cached = problemLimitsCache.getIfPresent(problemId);
        if (cached != null) {
            return cached;
        }
        Problem p = problemMapper.selectById(problemId);
        int[] limits;
        if (p == null) {
            limits = new int[]{1000, 131072};
        } else {
            int timeLimit = (p.getTimeLimit() != null && p.getTimeLimit() > 0) ? p.getTimeLimit() : 1000;
            int memLimit = (p.getMemoryLimit() != null && p.getMemoryLimit() > 0) ? p.getMemoryLimit() : 128;
            limits = new int[]{timeLimit, memLimit * 1024};
        }
        problemLimitsCache.put(problemId, limits);
        return limits;
    }

    @Override
    public List<TestCaseView> findTestCases(Long problemId) {
        if (problemId == null) return Collections.emptyList();
        return testCaseCache.get(problemId, this::loadTestCases);
    }

    private List<TestCaseView> loadTestCases(Long problemId) {
        List<ProblemCase> cases = problemCaseMapper.selectList(
                new LambdaQueryWrapper<ProblemCase>()
                        .eq(ProblemCase::getProblemId, problemId)
                        .orderByAsc(ProblemCase::getCaseOrder));
        return Collections.unmodifiableList(cases.stream().map(c -> new TestCaseView(
                c.getId(),
                c.getProblemId(),
                testCaseStorage.readByRelativePath(c.getInputFilePath()),
                testCaseStorage.readByRelativePath(c.getOutputFilePath()),
                c.getScore(),
                c.getSubtaskNo(),
                c.getCaseOrder(),
                c.getStatus()
        )).toList());
    }

    @Override
    public boolean existsNotDeleted(Long problemId) {
        if (problemId == null) return false;
        Problem problem = problemMapper.selectById(problemId);
        return problem != null && !Integer.valueOf(1).equals(problem.getIsDeleted());
    }
}
