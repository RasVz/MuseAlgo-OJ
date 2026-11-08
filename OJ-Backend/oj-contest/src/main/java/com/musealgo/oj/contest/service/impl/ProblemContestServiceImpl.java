package com.musealgo.oj.contest.service.impl;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.contest.error.ContestErrorCode;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.musealgo.oj.contest.application.ContestManagementApplicationService;
import com.musealgo.oj.contest.application.ContestAccessPolicy;
import com.musealgo.oj.contest.entity.ProblemContest;
import com.musealgo.oj.contest.entity.vo.ContestProblemVO;
import com.musealgo.oj.contest.mapper.ProblemContestMapper;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.service.ContestService;
import com.musealgo.oj.contest.service.ProblemContestService;
import com.musealgo.oj.common.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 比赛题目配置服务实现。
 * <p>
 * 配置写入在这里完成题目默认值与比赛归属校验；查询的联表逻辑下沉到了 {@link ProblemContestMapper} 的
 * 定制 SQL 里——题目列表需要连problem 表带出标题与难度，用 QueryWrapper 拼多表连接既难读又易错，
 * 写在 XML 里更清晰。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProblemContestServiceImpl
        extends ServiceImpl<ProblemContestMapper, ProblemContest>
        implements ProblemContestService {

    private final ContestService contestService;
    private final ContestManagementApplicationService contestManagementApplicationService;

    /**
     * 新增单题到比赛。
     * <p>
     * 三个默认值在此集中填充（分值 100、顺序 0、可见 1），而不是留给数据库：
     * {@code visible} 默认 1 意味着新加的题立即对选手可见——
     * 若需要「先隐藏、开赛前再放出」，调用方必须显式传 0。
     * </p>
     *
     * @param problemContest 待添加的题目关联，需带 contestId 与 problemId
     * @return 新增记录的主键 ID
     * @throws RuntimeException 该题已在本场比赛中时抛出
     */
    @Override
    @Transactional
    public Long addProblemToContest(ProblemContest problemContest) {
        if (problemContest == null || problemContest.getContestId() == null || problemContest.getProblemId() == null) {
            throw new BusinessException(ContestErrorCode.CONTEST_INPUT_INVALID, "比赛和题目不能为空");
        }
        requireManagedContest(problemContest.getContestId());
        if (existsConfigured(problemContest.getContestId(), problemContest.getProblemId())) {
            throw new BusinessException(ContestErrorCode.CONTEST_PROBLEM_ALREADY_ADDED, "该题目已在竞赛中，请勿重复添加");
        }
        if (problemContest.getScore() == null) problemContest.setScore(100);
        if (problemContest.getProblemOrder() == null) problemContest.setProblemOrder(0);
        if (problemContest.getVisible() == null) problemContest.setVisible(1);
        problemContest.setIsDeleted(0);
        if (baseMapper.insert(problemContest) != 1) throw new IllegalStateException("比赛题目保存失败");
        return problemContest.getId();
    }

    /**
     * 批量添加题目。
     * <p>
     * 题目顺序从「本场现有最大顺序」往后递增，而不是从 1 开始——
     * 否则重复调用本方法会把新题的顺序与已有题撞车，页面上的题目顺序就会错乱。
     * </p>
     * <p>
     * 已存在的题目<b>跳过而非报错</b>：批量导入通常是「把这批题都加进去」的重试操作，
     * 因个别重复而整体失败会让重试永远无法成功。
     * </p>
     *
     * @return 只要入参非空即返回 {@code true}，表示「请求已被处理」；
     *         全部题目都已存在时不会插入任何行，调用方不应据此认为有新题加入
     */
    @Override
    @Transactional
    public boolean batchAddProblems(Long contestId, List<Long> problemIds) {
        if (problemIds == null || problemIds.isEmpty()) return false;
        requireManagedContest(contestId);

        // 顺序续接而非从0 开始，保证多次批量添加不会产生重复序号
        int order = baseMapper.getMaxOrderByContestId(contestId);
        List<ProblemContest> batch = new ArrayList<>();

        for (Long problemId : problemIds) {
            if (existsConfigured(contestId, problemId)) continue;
            ProblemContest pc = new ProblemContest();
            pc.setContestId(contestId);
            pc.setProblemId(problemId);
            pc.setProblemOrder(++order);
            // 批量导入场景下分值统一取 100，需要差异化分值时走 addProblemToContest 逐个添加
            pc.setScore(100);
            pc.setVisible(1);
            pc.setIsDeleted(0);
            batch.add(pc);
        }

        if (!batch.isEmpty()) {
            if (!saveBatch(batch)) throw new IllegalStateException("比赛题目批量保存失败");
        }
        return true;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 用 Mapper 里专门的逻辑删除语句而非先查后改：一步到位，
     * 也避免把「查到但已被别人删掉」这种竞态暴露给调用方。
     * </p>
     */
    @Override
    @Transactional
    public boolean removeFromContest(Long id) {
        ProblemContest relation = requireManagedRelation(id);
        return baseMapper.logicalDeleteById(relation.getId(), LocalDateTime.now()) > 0;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 更新时间由服务端赋值而非信任调用方传值，避免前端传旧值导致更新时间倒退。
     * </p>
     */
    @Override
    @Transactional
    public boolean updateProblemConfig(ProblemContest problemContest) {
        if (problemContest == null || problemContest.getId() == null) {
            throw new BusinessException(ContestErrorCode.CONTEST_INPUT_INVALID, "比赛题目关联 ID 不能为空");
        }
        ProblemContest existing = requireManagedRelation(problemContest.getId());
        if (problemContest.getContestId() != null
                && !Objects.equals(problemContest.getContestId(), existing.getContestId())) {
            requireManagedContest(problemContest.getContestId());
        }
        ProblemContest patch = new ProblemContest();
        patch.setId(existing.getId());
        patch.setProblemOrder(problemContest.getProblemOrder());
        patch.setScore(problemContest.getScore());
        patch.setVisible(problemContest.getVisible());
        patch.setUpdateTime(LocalDateTime.now());
        return baseMapper.updateById(patch) > 0;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 只更新 {@code visible} 一个字段，不触碰其余列，
     * 因此不需要先读出整行，避免「读-改-写」期间被并发覆盖。
     * </p>
     */
    @Override
    @Transactional
    public boolean toggleVisible(Long id, Integer visible) {
        ProblemContest relation = requireManagedRelation(id);
        return baseMapper.updateVisible(relation.getId(), visible) > 0;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 最后一个参数 0 是「排除逻辑删除记录」的标记，由 Mapper 的定制 SQL 消费。
     * </p>
     */
    @Override
    public List<ProblemContest> getContestProblems(Long contestId, Integer visible) {
        if (!Integer.valueOf(1).equals(visible)) {
            requireManagedContest(contestId);
        } else {
            ContestAccessPolicy.requireReadable(requireExistingContest(contestId));
        }
        return baseMapper.selectContestProblemsWithDetails(contestId, visible, 0);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 可见性传 {@code null} 表示「不按可见性过滤」，从而把隐藏题也一并返回；
     * 此时要求当前教师拥有该比赛，或当前用户是管理员。
     * </p>
     */
    @Override
    public List<ProblemContest> getContestProblemsAdmin(Long contestId) {
        requireManagedContest(contestId);
        return baseMapper.selectContestProblemsWithDetails(contestId, null, 0);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 刻意<b>覆盖</b>继承自 {@code ServiceImpl} 的同名方法：父类的实现走 MyBatis-Plus
     * 默认逻辑，无法排除本表特有的「联表带出标题/难度」需求，
     * 调用方却期望拿到完整信息，因此用定制查询替换。
     * </p>
     */
    @Override
    public ProblemContest getById(Long id) {
        return requireManagedRelation(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean checkExist(Long contestId, Long problemId) {
        requireManagedContest(contestId);
        return existsConfigured(contestId, problemId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ContestProblemVO> selectContestProblemList(Long contestId, Integer visible, Long userId) {
        // 选手视角固定只查 visible=1；null / 0 等未发布视图由比赛所属教师或管理员读取。
        if (!Integer.valueOf(1).equals(visible)) {
            requireManagedContest(contestId);
        } else {
            ContestAccessPolicy.requireReadable(requireExistingContest(contestId));
        }
        return baseMapper.selectContestProblemList(contestId, visible, userId);
    }

    private ProblemContest requireManagedRelation(Long relationId) {
        SecurityUtils.requireTeacherOrAdmin();
        if (relationId == null) {
            throw new BusinessException(ContestErrorCode.CONTEST_INPUT_INVALID, "比赛题目关联 ID 不能为空");
        }
        ProblemContest relation = baseMapper.selectById(relationId);
        if (relation == null) {
            throw new BusinessException(ContestErrorCode.CONTEST_PROBLEM_NOT_FOUND, "比赛题目关联不存在");
        }
        requireManagedContest(relation.getContestId());
        return relation;
    }

    private Contest requireManagedContest(Long contestId) {
        SecurityUtils.requireTeacherOrAdmin();
        return contestManagementApplicationService.requireManagedContest(contestId);
    }

    private Contest requireExistingContest(Long contestId) {
        if (contestId == null) {
            throw new BusinessException(ContestErrorCode.CONTEST_INPUT_INVALID, "比赛 ID 不能为空");
        }
        Contest contest = contestService.getById(contestId);
        if (contest == null) {
            throw new BusinessException(ContestErrorCode.CONTEST_NOT_FOUND, "比赛不存在");
        }
        return contest;
    }

    private boolean existsConfigured(Long contestId, Long problemId) {
        return baseMapper.countByContestAndProblem(contestId, problemId) > 0;
    }
}
