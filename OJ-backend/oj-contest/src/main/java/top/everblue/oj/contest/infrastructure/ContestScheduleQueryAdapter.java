package top.everblue.oj.contest.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.contest.ContestSchedule;
import top.everblue.oj.api.contest.ContestScheduleQuery;
import top.everblue.oj.contest.mapper.ContestMapper;
import top.everblue.oj.contest.mapper.VpAttemptMapper;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.entity.VpAttempt;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link ContestScheduleQuery} 的 oj-contest 侧实现，提供跨模块可见的比赛时间与状态判定。
 * <p>
 * 本类回答的全是「某场比赛此刻处于什么状态」这类问题，且需要被<b>其它模块</b>读取
 * （例如判定要不要对某次提交封榜、要不要触发赛后查重）。
 * 这些判定必须统一走同一份数据，否则会出现「oj-contest 认为比赛已结束、
 * 另一个模块认为还没结束」的时间线裂缝，因此禁止各模块自行写一份时间比较。
 * </p>
 *
 * <h3>统一的时间窗口径</h3>
 * <p>
 * 所有「进行中」判定一律取<b>闭区间</b>（{@code startTime <= now <= endTime}），
 * 且都传入了由调用方提供的 {@code now} 或取一次 {@code LocalDateTime.now()}，
 * 保证同一次判定内部使用同一个时间基准——多次取当前时间会在边界处产生自相矛盾的结论。
 * </p>
 */
@Service
public class ContestScheduleQueryAdapter implements ContestScheduleQuery {

    private final ContestMapper contestMapper;
    private final VpAttemptMapper vpAttemptMapper;

    public ContestScheduleQueryAdapter(ContestMapper contestMapper, VpAttemptMapper vpAttemptMapper) {
        this.contestMapper = contestMapper;
        this.vpAttemptMapper = vpAttemptMapper;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 返回 {@link Optional} 而非 {@code null}，让调用方在编译期就被迫处理「比赛不存在」的情况。
     * </p>
     */
    @Override
    public Optional<ContestSchedule> findById(Long contestId) {
        return Optional.ofNullable(contestMapper.selectById(contestId))
                .map(contest -> new ContestSchedule(
                        contest.getId(), contest.getStartTime(), contest.getEndTime(),
                        contest.getMode(), contest.getAuthorId()));
    }

    /**
     * 查出所有正在进行中的 OI 比赛 ID。
     * <p>
     * 只看 OI 模式：ACM 比赛进行中不涉密，判定结果对调用方的行为没有影响，
     * 把它们也纳入只会让每次判定的结果集变大。
     * </p>
     *
     * @return 进行中的 OI 比赛 ID 集合；无则返回空集合
     */
    @Override
    public Set<Long> findRunningOiContestIds() {
        LocalDateTime now = LocalDateTime.now();
        return contestMapper.selectList(new LambdaQueryWrapper<Contest>()
                        .eq(Contest::getMode, "OI")
                        // le/ge 而非 lt/gt：起止时刻本身算「进行中」，
                        // 与 ContestRankingSseController、ContestController 的口径保持一致
                        .le(Contest::getStartTime, now)
                        .ge(Contest::getEndTime, now))
                .stream()
                .map(Contest::getId)
                .collect(Collectors.toSet());
    }

    /**
     * 判断某个 VP 会话此刻是否仍在有效作答期内。
     * <p>
     * 三个条件缺一不可：会话存在、状态为 RUNNING、{@code endTime} 未到。
     * 只看状态是不够的——用户可能长时间不交卷，会话在数据上仍是 RUNNING 但早已超时，
     * 若不比对时间就会让「过期」的会话继续被接受提交。
     * </p>
     *
     * @param vpAttemptId VP 会话 ID，可为 {@code null}
     * @return 仍在有效期内返回 {@code true}
     */
    @Override
    public boolean isVpAttemptRunning(Long vpAttemptId) {
        if (vpAttemptId == null) {
            return false;
        }
        VpAttempt attempt = vpAttemptMapper.selectById(vpAttemptId);
        return attempt != null
                && VpAttempt.STATUS_RUNNING.equals(attempt.getStatus())
                && attempt.getEndTime() != null
                && !attempt.getEndTime().isBefore(LocalDateTime.now());
    }

    /**
     * 找出所有「OI 比赛 + 正在作答中」的 VP 会话 ID，供批量封榜判定使用。
     * <p>
     * 两段查询而非一条联表 SQL：先取进行中的会话（只关心 id 与 contestId），
     * 再用它们反查哪些是 OI 比赛，最后过滤。这样做避免了「先在 contest 上过滤 OI、
     * 再关联 vp_attempt」时因一张表数据量大而让优化器选错驱动表。
     * </p>
     *
     * @return 符合条件的 VP 会话 ID 集合；无则返回空集合
     */
    @Override
    public Set<Long> findRunningOiVpAttemptIds() {
        LocalDateTime now = LocalDateTime.now();
        // 只查未超时的 RUNNING 会话，与 isVpAttemptRunning 的口径保持一致
        List<VpAttempt> running = vpAttemptMapper.selectList(new LambdaQueryWrapper<VpAttempt>()
                .eq(VpAttempt::getStatus, VpAttempt.STATUS_RUNNING)
                .ge(VpAttempt::getEndTime, now));
        if (running.isEmpty()) {
            return Set.of();
        }
        Set<Long> contestIds = running.stream().map(VpAttempt::getContestId).collect(Collectors.toSet());
        Set<Long> oiContestIds = contestMapper.selectList(new LambdaQueryWrapper<Contest>()
                        .in(Contest::getId, contestIds)
                        .eq(Contest::getMode, "OI"))
                .stream().map(Contest::getId).collect(Collectors.toSet());
        return running.stream()
                .filter(attempt -> oiContestIds.contains(attempt.getContestId()))
                .map(VpAttempt::getId)
                .collect(Collectors.toSet());
    }

    /**
     * 找出「已结束超过宽限期、且尚未执行赛后查重」的比赛，供后台任务补跑查重。
     * <p>
     * <b>为什么要 graceMinutes 宽限：</b>比赛一结束就立刻查重是不可靠的——
     * 此时判题队列里可能还压着该比赛最后时刻的提交未落库，查重会漏掉这些代码。
     * 等待若干分钟让队列排空，结论才完整。
     * </p>
     * <p>
     * {@code plagiarismChecked} 作为幂等标记：本方法返回的都是「待处理」的比赛，
     * 调用方处理完需把该标记置 1，因此重复调用不会重复处理同一场比赛。
     * </p>
     *
     * @param graceMinutes 结束后的宽限分钟数；负数按 0 处理（即无宽限）
     * @return 待查重的比赛 ID 集合
     */
    @Override
    public Set<Long> findEndedUncheckedContestIds(int graceMinutes) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(Math.max(graceMinutes, 0));
        return contestMapper.selectList(new LambdaQueryWrapper<Contest>()
                        .eq(Contest::getIsDeleted, 0)
                        .eq(Contest::getPlagiarismChecked, 0)
                        // le 而非 lt：恰好在宽限期边界结束的比赛也应当被纳入
                        .le(Contest::getEndTime, deadline))
                .stream()
                .map(Contest::getId)
                .collect(Collectors.toSet());
    }
}
