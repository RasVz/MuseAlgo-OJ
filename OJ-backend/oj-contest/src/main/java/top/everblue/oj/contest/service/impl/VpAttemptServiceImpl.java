package top.everblue.oj.contest.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.api.problem.ProblemQuery;
import top.everblue.oj.api.submission.ContestVpQuery;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.entity.ContestRank;
import top.everblue.oj.contest.entity.VpAttempt;
import top.everblue.oj.contest.entity.vo.CorrectionVO;
import top.everblue.oj.contest.entity.vo.VpAttemptVO;
import top.everblue.oj.contest.mapper.ContestRankMapper;
import top.everblue.oj.contest.mapper.ProblemContestMapper;
import top.everblue.oj.contest.mapper.VpAttemptMapper;
import top.everblue.oj.contest.service.ContestService;
import top.everblue.oj.contest.service.VpAttemptService;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 虚拟赛（VP）与赛后订正追踪（B4）。
 * <p>
 * 实现 VP 的计时、交卷冻结与名次推算。
 * VP 与正式比赛<b>共用题库与成绩表，但彻底隔离</b>：VP 的成绩只写 vp_attempt，
 * 其「名次」是拿自己的成绩与正式榜逐个比较后<b>现场推算</b>出来的，
 * 不会往 contest_rank 里插任何行。因此 VP 不会挤占真实选手的名次，
 * 也不受正式榜固化时机的影响。
 * </p>
 *
 * <h3>为什么 VP 必须先加锁再判定</h3>
 * <p>
 * {@link #start} 先取比赛行的悲观锁，再判断是否已过结束时间。
 * 若不加锁，两个并发请求可能都在对方插入 RUNNING 记录之前通过了「无进行中会话」的检查，
 * 产生两个并行的计时会话，用户就凭空多出了一份可用时长。
 * 行锁把「查会话 + 建会话」串行化，这一步不能省。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VpAttemptServiceImpl implements VpAttemptService {

    /**
     * 赛后订正窗口天数。超出这个窗口后的 AC 不再计入订正统计——
     * 窗口过长会让「赛后很久才做出来」的题也被算作订正成功，指标失去意义。
     */
    private static final int CORRECTION_WINDOW_DAYS = 7;

    private final VpAttemptMapper vpAttemptMapper;
    private final ContestService contestService;
    private final ContestRankMapper contestRankMapper;
    private final ProblemContestMapper problemContestMapper;
    private final ProblemQuery problemQuery;
    private final ContestVpQuery contestVpQuery;

    /**
     * 开始一次 VP。
     * <p>
     * 三条分支：比赛未结束则拒绝；已有进行中会话则续用（超时则顺带交卷）；
     * 否则新建会话。
     * </p>
     *
     * @param contestId 比赛 ID
     * @param userId 用户 ID
     * @return 当前 VP 会话视图
     * @throws IllegalArgumentException 比赛不存在时抛出
     * @throws IllegalStateException 比赛尚未结束时抛出——虚拟参赛必须发生在正式赛程之后，
     *         否则选手能通过反复 VP 提前看到尚未发布的题目
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public VpAttemptVO start(Long contestId, Long userId) {
        // 加锁读比赛行：把「检查是否已有进行中会话」与「新建会话」串行化，
        // 否则并发点击会创建出两个并行会话，等于凭空多给一份时长
        Contest contest = contestService.getByIdForUpdate(contestId);
        if (contest == null) {
            throw new IllegalArgumentException("比赛不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        // 边界取「now < endTime 即未结束」：恰好在结束时刻发起 VP 应当放行
        if (contest.getEndTime() == null || now.isBefore(contest.getEndTime())) {
            throw new IllegalStateException("比赛进行中或未开始，暂不能虚拟参赛");
        }
        VpAttempt running = findRunning(contestId, userId);
        if (running != null) {
            // 已有会话时不新建：VP 可反复重考，每次重考都叠加时长会让刷分变得无成本。
            // 但若旧会话已超时，就在此处顺带交卷，避免留下永远处于 RUNNING 的僵尸记录
            if (VpAttempt.STATUS_SETTLING.equals(running.getStatus()) || !running.getEndTime().isAfter(now)) {
                return finalize(running);
            }
            return toVO(running);
        }
        // 沿用比赛配置的时长；配置缺失时兜底 120 分钟。
        // 用兜底值而非报错：老比赛的 duration 可能为空，不能因此让 VP 功能整体不可用
        int duration = contest.getDuration() == null ? 120 : contest.getDuration();
        VpAttempt attempt = new VpAttempt();
        attempt.setContestId(contestId);
        attempt.setUserId(userId);
        attempt.setStatus(VpAttempt.STATUS_RUNNING);
        attempt.setStartTime(now);
        attempt.setEndTime(now.plusMinutes(duration));
        attempt.setScore(0);
        attempt.setSolvedCount(0);
        attempt.setPenaltyTime(0);
        attempt.setCreateTime(now);
        attempt.setUpdateTime(now);
        vpAttemptMapper.insert(attempt);
        return toVO(attempt);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 没有进行中的会话时<b>不报错</b>而是返回最近一次会话：
     * 前端的「交卷」按钮可能被重复点击，或页面刷新后又点了一次，
     * 这两种情况都应表现为幂等成功，而不是给用户一个莫名的错误。
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public VpAttemptVO finish(Long contestId, Long userId) {
        contestService.getByIdForUpdate(contestId);
        VpAttempt running = findRunning(contestId, userId);
        if (running == null) {
            return toVO(latest(contestId, userId));
        }
        return finalize(running);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 读接口顺带承担了「超时自动交卷」的副作用：只要有访问，就会把过期的 RUNNING 会话固化下来。
     * 这样即使用户再也不点交卷，成绩也会在其下次访问时被正确计算。
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public VpAttemptVO getMy(Long contestId, Long userId) {
        contestService.getByIdForUpdate(contestId);
        VpAttempt running = findRunning(contestId, userId);
        if (running != null) {
            if (VpAttempt.STATUS_SETTLING.equals(running.getStatus()) || !running.getEndTime().isAfter(LocalDateTime.now())) {
                return finalize(running);
            }
            return toVO(running);
        }
        return toVO(latest(contestId, userId));
    }

    /**
     * 交卷固化：算出最终成绩与名次，并把状态置为 FINISHED。
     * <p>
     * <b>名次是推算而非存储排名表的结果：</b>遍历正式榜，逐个判断「正式榜中这人是否比我强」，
     * 计数加一即我的名次。这样 VP 不产生任何榜单写入，代价是 O(正式榜人数) 的一次遍历，
     * 在数百人规模下完全可接受。
     * </p>
     * <p>
     * 两套模式用不同的比较口径：OI 看总分高低，ACM 先看过题数、再看罚时更少者更优。
     * </p>
     */
    private VpAttemptVO finalize(VpAttempt attempt) {
        LocalDateTime now = LocalDateTime.now();
        Contest contest = contestService.getById(attempt.getContestId());
        if (VpAttempt.STATUS_RUNNING.equals(attempt.getStatus())) {
            attempt.setStatus(VpAttempt.STATUS_SETTLING);
            if(attempt.getEndTime().isAfter(now)) attempt.setEndTime(now);
            attempt.setSubmitTime(now);
            attempt.setUpdateTime(now);
            vpAttemptMapper.updateById(attempt);
        }
        if(contest==null || contest.getRankFinalizedAt()==null || contestVpQuery.hasPending(attempt.getId())) return toVO(attempt);
        boolean oiMode = "OI".equalsIgnoreCase(contest.getMode());
        int score = oiMode ? contestVpQuery.findBestScores(attempt.getId())
                .stream().mapToInt(ContestVpQuery.VpProblemScore::bestScore).sum() : 0;
        AcmResult acm = oiMode ? new AcmResult(0, 0) : calculateAcmResult(attempt);
        List<ContestRank> ranks = contestRankMapper.selectList(
                new LambdaQueryWrapper<ContestRank>()
                        .eq(ContestRank::getContestId, attempt.getContestId()));
        int rank = 1;
        for (ContestRank r : ranks) {
            int rScore = r.getTotalScore() == null ? 0 : r.getTotalScore();
            int rSolved = r.getSolvedCount() == null ? 0 : r.getSolvedCount();
            int rPenalty = r.getPenaltyTime() == null ? 0 : r.getPenaltyTime();
            boolean better = oiMode
                    ? rScore > score
                    : rSolved > acm.solvedCount()
                            || (rSolved == acm.solvedCount() && rPenalty < acm.penaltyTime());
            if (better) {
                rank++;
            }
        }
        attempt.setStatus(VpAttempt.STATUS_FINISHED);
        attempt.setScore(score);
        attempt.setSolvedCount(acm.solvedCount());
        attempt.setPenaltyTime(acm.penaltyTime());
        attempt.setRankNum(rank);
        // 参与人数 = 正式榜人数 + 我自己。注意这里比的是正式榜而非其他 VP 参与者，
        // 因此同场的多个 VP 之间拿到的名次互不排斥（各自都与同一份正式榜比较）
        attempt.setTotalParticipants(ranks.size() + 1);
        attempt.setUpdateTime(now);
        vpAttemptMapper.updateById(attempt);
        log.info("【VP】完成 contestId={}, userId={}, score={}, rank={}/{}",
                attempt.getContestId(), attempt.getUserId(), score, rank, ranks.size() + 1);
        return toVO(attempt);
    }

    /**
     * 汇总 ACM 模式的过题数与总罚时。
     * <p>
     * <b>遍历顺序即提交顺序</b>，这是罚时能算对的前提：
     * 只有在遍历中先遇到某题的失败提交、再遇到它的 AC，才能把失败次数计入该题罚时。
     * 若把提交打乱成无序集合，罚时会退化成「只算用时、不算错题」。
     * </p>
     * <p>
     * 罚时公式与正式榜一致：距 VP 开始的分钟数 + 该题此前失败次数 × 20。
     * 已 AC 过的题后续提交直接跳过，{@code continue} 保证它们不会重复计入过题数或罚时。
     * </p>
     *
     * @return 过题数与总罚时的不可变二元组
     */
    private AcmResult calculateAcmResult(VpAttempt attempt) {
        Map<Long, Integer> wrongAttempts = new java.util.HashMap<>();
        Set<Long> solvedProblems = new java.util.HashSet<>();
        int penalty = 0;
        for (ContestVpQuery.VpSubmission submission : contestVpQuery.findSubmissions(attempt.getId())) {
            if (solvedProblems.contains(submission.problemId())) {
                continue;
            }
            if (submission.status() != null && submission.status() == 1) {
                solvedProblems.add(submission.problemId());
                // 用 VP 的 startTime 而非比赛开赛时间计时：VP 有自己独立的起止，计时基准必须是它
                // Math.max 兜底，防止提交时间早于会话开始（时钟异常）时算出负罚时
                long minutes = Math.max(0,
                        Duration.between(attempt.getStartTime(), submission.submittedAt()).toMinutes());
                // 此处取到的失败次数是「AC 之前」的失败数，不含本次，符合 ICPC 规则
                penalty += (int) minutes + wrongAttempts.getOrDefault(submission.problemId(), 0) * 20;
            } else {
                wrongAttempts.merge(submission.problemId(), 1, Integer::sum);
            }
        }
        return new AcmResult(solvedProblems.size(), penalty);
    }

    /** ACM 模式的一次 VP 成绩二元组。 */
    private record AcmResult(int solvedCount, int penaltyTime) {
    }

    /**
     * {@inheritDoc}
     * <p>
     * 三次查询分别取「本场题目列表」「赛中每题最好状态」「赛后窗口内的 AC 题目」，
     * 再在内存里按 problemId 对齐。之所以在内存做 join 而不在 SQL 里一次查出，
     * 是因为数据来源跨越 oj-api 的只读契约接口，本模块拿不到统一的连接条件；
     * 题目数量在数十量级，内存 join 的开销可忽略。
     * </p>
     */
    @Override
    public List<CorrectionVO> correction(Long contestId, Long userId) {
        Contest contest = contestService.getById(contestId);
        if (contest == null || contest.getEndTime() == null) {
            return List.of();
        }
        List<Long> problemIds = problemContestMapper.selectList(
                        new LambdaQueryWrapper<top.everblue.oj.contest.entity.ProblemContest>()
                                .select(top.everblue.oj.contest.entity.ProblemContest::getProblemId)
                                .eq(top.everblue.oj.contest.entity.ProblemContest::getContestId, contestId))
                .stream().map(top.everblue.oj.contest.entity.ProblemContest::getProblemId).distinct().toList();
        if (problemIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Byte> contestStatus = contestVpQuery.findContestResults(contestId, userId).stream()
                .collect(Collectors.toMap(ContestVpQuery.ContestProblemResult::problemId,
                        ContestVpQuery.ContestProblemResult::bestStatus));
        LocalDateTime from = contest.getEndTime();
        // 窗口从「比赛结束」起算而非「用户交卷」起算：所有人都以同一个时间点为准，
        // 否则先交卷的人窗口更长、后交卷的人窗口更短，指标就不可比了
        LocalDateTime to = from.plusDays(CORRECTION_WINDOW_DAYS);
        Map<Long, Boolean> corrected = contestVpQuery.findPostContestAc(userId, problemIds, from, to).stream()
                .collect(Collectors.toMap(ContestVpQuery.VpProblemScore::problemId, p -> true));
        Map<Long, String> titleMap = problemQuery.findTitleBatch(problemIds);

        List<CorrectionVO> result = new ArrayList<>();
        for (Long problemId : problemIds) {
            CorrectionVO vo = new CorrectionVO();
            vo.setProblemId(problemId);
            vo.setProblemTitle(titleMap.getOrDefault(problemId, "题目 #" + problemId));
            vo.setContestStatus(statusLabel(contestStatus.get(problemId)));
            vo.setCorrected(corrected.getOrDefault(problemId, false));
            result.add(vo);
        }
        return result;
    }

    /**
     * 查找该用户在本场进行中的 VP 会话。
     * <p>
     * 用 {@code LIMIT 1} 而非依赖唯一约束：出现多行说明已有脏数据（并发创建的历史遗留），
     * 加上 LIMIT 能保证方法照常返回、不会抛「结果不唯一」异常打断整个请求。
     * </p>
     *
     * @return 进行中的会话；不存在时返回 {@code null}
     */
    private VpAttempt findRunning(Long contestId, Long userId) {
        return vpAttemptMapper.selectOne(new LambdaQueryWrapper<VpAttempt>()
                .eq(VpAttempt::getContestId, contestId)
                .eq(VpAttempt::getUserId, userId)
                .in(VpAttempt::getStatus, VpAttempt.STATUS_RUNNING,VpAttempt.STATUS_SETTLING)
                .orderByDesc(VpAttempt::getId).last("LIMIT 1"));
    }

    /**
     * 取最近一次 VP 会话（不论状态），用于「当前没有进行中会话」时回显上一次成绩。
     * <p>
     * 按自增 id 倒序而非 createTime 倒序：id 单调递增不受并发写入与时间精度影响，
     * 用时间排序在同秒创建的两条记录间结果不确定。
     * </p>
     *
     * @return 最近一次会话；从未参加过 VP 时返回 {@code null}
     */
    private VpAttempt latest(Long contestId, Long userId) {
        return vpAttemptMapper.selectOne(new LambdaQueryWrapper<VpAttempt>()
                .eq(VpAttempt::getContestId, contestId)
                .eq(VpAttempt::getUserId, userId)
                .orderByDesc(VpAttempt::getId)
                .last("LIMIT 1"));
    }

    /**
 * 实体转 VO，并在此处计算倒计时。
     * <p>
     * 剩余秒数<b>必须由服务端算</b>：前端拿 startTime/endTime 自行倒计时的话，
     * 用户改系统时间就能凭空获得额外时长。等响应返回时已经过去若干毫秒，
     * 前端还需要自行减去这段网络耗时才能对齐，这正是这里统一在服务端定格的原因。
     * </p>
     *
     * @param attempt 会话实体，可为 {@code null}
     * @return 视图对象；{@code attempt} 为 {@code null} 时返回 {@code null}
     */
    private static VpAttemptVO toVO(VpAttempt attempt) {
        if (attempt == null) {
            return null;
        }
        VpAttemptVO vo = new VpAttemptVO();
        vo.setId(attempt.getId());
        vo.setContestId(attempt.getContestId());
        vo.setStatus(attempt.getStatus());
        vo.setStartTime(attempt.getStartTime());
        vo.setEndTime(attempt.getEndTime());
        vo.setSubmitTime(attempt.getSubmitTime());
        vo.setScore(attempt.getScore());
        vo.setSolvedCount(attempt.getSolvedCount());
        vo.setPenaltyTime(attempt.getPenaltyTime());
        vo.setRankNum(attempt.getRankNum());
        vo.setTotalParticipants(attempt.getTotalParticipants());
        // 已交卷则剩余时间恒为 0；Math.max 兜住「已超时但尚未交卷」的瞬间，
        // 此时返回 0 而不是负数，避免前端拿负秒数做倒计时
        vo.setRemainingSeconds(VpAttempt.STATUS_RUNNING.equals(attempt.getStatus())
                ? Math.max(0, java.time.Duration.between(LocalDateTime.now(), attempt.getEndTime()).getSeconds())
                : 0L);
        return vo;
    }

    /**
     * 把数字状态码转成可读标签。
     * <p>
     * <b>用 switch 穷举而非查表映射，是刻意的：</b>新增一种判题结论时，
     * 编译器会在此处报错提醒同步更新，避免出现「前端拿到未定义字符串」。
     * {@code default} 分支返回 {@code PENDING} 而不是抛异常——
     * 新出现的状态码不应让整个订正列表加载失败。
     * </p>
     *
     * @param status 数字状态码，可为 {@code null}
     * @return 状态标签；{@code null} 表示从未提交
     */
    private static String statusLabel(Byte status) {
        if (status == null) return "未提交";
        return switch (status) {
            case 1 -> "AC";
            case 2 -> "WA";
            case 3 -> "CE";
            case 4 -> "RE";
            case 5 -> "TLE";
            default -> "PENDING";
        };
    }
}
