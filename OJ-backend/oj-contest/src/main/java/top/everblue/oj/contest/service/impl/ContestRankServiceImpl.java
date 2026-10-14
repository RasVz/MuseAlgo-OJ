package top.everblue.oj.contest.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.api.submission.ContestSubmission;
import top.everblue.oj.api.submission.ContestSubmissionQuery;
import top.everblue.oj.contest.entity.ContestRank;
import top.everblue.oj.contest.entity.UserRating;
import top.everblue.oj.contest.entity.vo.ContestRankVO;
import top.everblue.oj.contest.mapper.ContestRankMapper;
import top.everblue.oj.contest.service.ContestRankService;
import top.everblue.oj.contest.service.RankRedisService;
import top.everblue.oj.contest.service.RatingCalculator;
import top.everblue.oj.contest.service.UserRatingService;
import top.everblue.oj.api.user.UserDisplayInfo;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.api.user.UserRatingCommand;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 榜单服务实现，负责「实时榜（Redis）」与「最终榜（MySQL）」之间的切换点。
 * <p>
 * 本类同时是<b>读</b>的两条入口与<b>写</b>的两个时机的所在地：
 * </p>
 * <ul>
 *   <li>比赛进行中：消费者写 Redis，读接口走 {@link RankRedisService}。</li>
 *   <li>比赛结束时：{@link #finalizeContest} 把 Redis 内容固化进 contest_rank 表，
 *       顺带计算并落库 rating，最后清空 Redis 缓存。</li>
 * </ul>
 *
 * <h3>finalizeContest 的顺序不可调换</h3>
 * <p>
 * 固化顺序是「删旧 → 批量插新 → 算 rating → 清 Redis」，每一步都依赖上一步：
 * </p>
 * <ol>
 *   <li><b>先删后插</b>：用「删除本场全部旧记录」而非「若有则拒绝」，
 *       使该操作<b>天然幂等</b>——管理员误触两次「结束比赛」不会因唯一键冲突而中途失败，
 *       也就不会留下「删了旧的但没插新的」这种空榜。</li>
 *   <li><b>整个方法一个事务</b>（{@code rollbackFor = Exception.class}）：
 *       成绩快照与 rating 流水必须同生共死。若拆成两个事务，会出现「成绩已固化、rating 没算」
 *       的状态，而此时比赛已结束、实时榜将被清空，选手的评分就永远丢了。</li>
 *   <li><b>清 Redis 放最后</b>：它在事务内但作用于外部系统，
 *       一旦前面失败回滚，Redis 中的实时榜仍然完好——这正是清缓存必须放在最后的原因，
 *       顺序颠倒会让失败的固化把唯一的成绩副本一起抹掉。</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContestRankServiceImpl implements ContestRankService {

    private final ContestRankMapper contestRankMapper;
    private final RankRedisService rankRedisService;
    private final ContestSubmissionQuery contestSubmissionQuery;
    private final RatingCalculator ratingCalculator;
    private final UserRatingService userRatingService;
    private final UserQuery userQuery;
    private final UserRatingCommand userRatingCommand;

    /**
     * 比赛结束时的成绩固化入口：落最终榜 + 算 rating + 清实时榜缓存。
     * <p>
     * <b>幂等</b>：重复执行会以最新 Redis 快照覆盖旧结果，不会产生重复行或重复评分。
     * </p>
     *
     * @param contestId 比赛 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finalizeContest(Long contestId) {
        List<ContestRankVO> rankList = rankRedisService.getAllRanks(contestId);
        if (rankList == null) {
            rankList = new ArrayList<>();
        }

        List<ContestRank> entities = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (ContestRankVO vo : rankList) {
            ContestRank entity = new ContestRank();
            entity.setContestId(vo.getContestId());
            entity.setUserId(vo.getUserId());
            entity.setRankNum(vo.getRankNum());
            entity.setSolvedCount(vo.getSolvedCount());
            entity.setPenaltyTime(vo.getPenaltyTime());
            entity.setTotalScore(vo.getTotalScore());
            // 实时榜下这两个字段为 null（评分要等此刻才算），落库时保持 null 是预期行为：
            // 由 user_rating 流水承担评分的权威记录，不在成绩快照里复制一份可能过时的副本
            entity.setRatingBefore(vo.getRatingBefore());
            entity.setRatingAfter(vo.getRatingAfter());
            entity.setAward(vo.getAward());
            // 实时榜的 isVisible 恒为 1，兜底 null 是为了满足该列非空约束
            entity.setIsVisible(vo.getIsVisible() == null ? 1 : vo.getIsVisible());
            entity.setIsDeleted(0);
            entity.setCreateTime(now);
            entity.setUpdateTime(now);
            // 创建/更新人固定为0（系统任务），比赛固化和具体管理员无关
            entity.setCreateBy(0L);
            entity.setUpdateBy(0L);
            entities.add(entity);
        }

        // 先物理删除本场旧记录再批量插入：这是 finalizeContest 幂等的来源，
        // 也让「重复点结束比赛」不会撞唯一键约束而在事务中途失败
        contestRankMapper.deleteByContestId(contestId);

        if (!entities.isEmpty()) {
            // 批量插入而非逐条：一场比赛动辄数百人，逐条 insert 的往返次数不可接受
            contestRankMapper.batchInsert(entities);
        }

        // ---- Rating 计算 ----
        if (!rankList.isEmpty()) {
            calculateAndSaveRatings(contestId, rankList);
        }

        // 必须在最后：前面任一步失败回滚时，Redis 中的实时榜是成绩的唯一副本，
        // 先清缓存会让失败的操作把数据一起毁掉
        rankRedisService.deleteContestRankCache(contestId);
    }

    /**
     * 计算并保存所有参赛者的 rating 变化。
     * rankList 已按排名升序（rankNum=1 为冠军）。
     * <p>
     * 三步：算 delta（{@link RatingCalculator}）→ 写评分流水（图表数据源）
     * → 更新用户当前评分（下次计算的输入）。
     * 后两步都必须在同一事务内，否则流水与当前分会对不上。
     * </p>
     */
    private void calculateAndSaveRatings(Long contestId, List<ContestRankVO> rankList) {
        List<Long> userIds = rankList.stream().map(ContestRankVO::getUserId).toList();
        // 批量取当前评分：逐个查询在数百人规模下往返过多
        Map<Long, Integer> currentRatingMap = userRatingCommand.findRatings(userIds);
        // 无评分记录的选手（首次参赛）按初始分 1200 参与计算，
        // 与 Elo 体系的起点保持一致；此处若改为 0 会让 log10 走兜底、整体分数被拉歪
        List<Integer> currentRatings = userIds.stream()
                .map(id -> currentRatingMap.getOrDefault(id, 1200)).toList();

        List<UserRating> ratingRecords = ratingCalculator.calculate(contestId, userIds, currentRatings);
        userRatingService.batchSave(ratingRecords);

        // 用 ratingRecords 里的新分回写，而非再算一次：避免引入第二套计算逻辑导致分叉。
        // LinkedHashMap 仅为保证更新顺序与名次一致，便于排查问题；merge 取后者以防重复 userId 静默丢更新
        Map<Long, Integer> ratingUpdates = ratingRecords.stream()
                .collect(Collectors.toMap(UserRating::getUserId, UserRating::getRating, (a, b) -> b, LinkedHashMap::new));
        userRatingCommand.batchUpdateRatings(ratingUpdates);

        log.info("Rating 计算完成: contestId={}, participants={}", contestId, ratingRecords.size());
    }

    /**
     * 读取实时榜前 n 名，{@code n} 为空时默认取 10 名。
     * <p>
     * 只读 Redis，不回退数据库：比赛进行中 contest_rank 表必然是空的，
     * 回退逻辑只会掩盖「实时榜还没建立」这类问题。
     * </p>
     */
    @Override
    public List<ContestRankVO> getRealtimeTopN(Long contestId, Integer n) {
        return rankRedisService.getTopN(contestId, n == null ? 10 : n);
    }

    /** 读取实时榜中某选手的名次，不在榜上时返回 {@code null}。 */
    @Override
    public Long getRealtimeUserRank(Long contestId, Long userId) {
        return rankRedisService.getUserRank(contestId, userId);
    }

    /**
     * 读取已固化的最终榜，按名次升序；未固化时返回空列表。
     */
    @Override
    public List<ContestRank> getFinalRankList(Long contestId) {
        if (contestId == null) {
            return Collections.emptyList();
        }
        return contestRankMapper.selectList(
                new LambdaQueryWrapper<ContestRank>()
                        .eq(ContestRank::getContestId, contestId)
                        .eq(ContestRank::getIsDeleted, 0)
                        .orderByAsc(ContestRank::getRankNum)
        );
    }

    /**
     * 从历史提交重建实时榜。
     * <p>
     * <b>为什么需要重建：</b>实时榜只存在于 Redis，且 key 会过期。
     * 一场进行了 5 天、期间无人提交的比赛，其榜单可能已过期消失；
     * 此时若直接开榜，选手会看到空榜。因此任何时候都能「从提交流水重放一遍」把榜单恢复出来，
     * 这是实时榜能自我修复的关键。
     * </p>
     * <p>
     * <b>正确性依赖幂等而非顺序：</b>提交记录按提交时间顺序重放，
     * 每条都调用同一个 {@link RankRedisService#submitProblem}，由它内部的
     * 「已 solved 则短路」「OI 只取最高分」保证重复重放结果一致。
     * 因此本方法<b>不需要</b>预先排序，也允许被反复调用。
     * </p>
     *
     * @param contestId 比赛 ID，为 {@code null} 时不做任何事
     */
    @Override
    public void rebuildRealtimeRankFromSubmission(Long contestId) {
        if (contestId == null) {
            return;
        }

        // 先清空：否则重放会把已有的错数据与新算的结果叠加，无法收敛
        rankRedisService.deleteContestRankCache(contestId);

        List<ContestSubmission> submissions = contestSubmissionQuery.findByContestId(contestId);
        if (submissions == null || submissions.isEmpty()) {
            return;
        }

        for (ContestSubmission submission : submissions) {
            // 逐条做防御性校验而非直接解引用：这是全量重放数据源的入口，
            // 单条脏数据不应中断整场重建（中断会导致榜单残缺，比一条错误更糟）
            if (submission == null) {
                continue;
            }
            if (submission.contestId() == null || !contestId.equals(submission.contestId())) {
                continue;
            }
            // 关键字段缺失的提交无法计入榜单（缺 userId/problemId 无法定位，缺时间无法算罚时），跳过
            if (submission.userId() == null || submission.problemId() == null || submission.submittedAt() == null) {
                continue;
            }

            // 榜单只区分「已通过」与「未通过」两态，不区分 WA/TLE/RE——
            // 榜单语义上错误尝试都等价于一次失败尝试，用 WA 作为统一占位值
            String rankStatus = isAcceptedStatus(submission.status()) ? "AC" : "WA";
            rankRedisService.submitProblem(
                    contestId,
                    submission.userId(),
                    submission.problemId(),
                    rankStatus,
                    submission.score() == null ? 0 : submission.score(),
                    submission.submittedAt()
            );
        }
    }

    /**
     * 判断最终榜是否已固化，用于把「结束比赛」操作做成幂等。
     */
    @Override
    public boolean hasFinalizedRank(Long contestId) {
        if (contestId == null) {
            return false;
        }
        Long count = contestRankMapper.selectCount(
                new LambdaQueryWrapper<ContestRank>()
                        .eq(ContestRank::getContestId, contestId)
                        .eq(ContestRank::getIsDeleted, 0)
        );
        return count != null && count > 0;
    }

    /**
     * 导出最终成绩为 CSV 文本。
     * <p>
     * 手写 CSV 而不用现成库，是因为这里只有「一种分隔符 + 一种转义规则」的窄需求，
     * 引入依赖换不来好处。用户信息走批量接口一次性取回，避免逐行查用户造成的 N+1。
     * </p>
     *
     * @param contestId 比赛 ID
     * @return 带表头的 CSV 文本；无成绩时只返回表头行
     */
@Override
    public String exportCsv(Long contestId) {
        List<ContestRank> ranks = contestRankMapper.selectList(
                new LambdaQueryWrapper<ContestRank>()
                        .eq(ContestRank::getContestId, contestId)
                        .orderByAsc(ContestRank::getRankNum));
        StringBuilder csv = new StringBuilder();
        csv.append("名次,用户,解题数,罚时,总分,Rating前,Rating后,奖项\n");
        if (ranks.isEmpty()) {
            return csv.toString();
        }
        Map<Long, UserDisplayInfo> userMap = userQuery.findDisplayInfoBatch(
                ranks.stream().map(ContestRank::getUserId).collect(Collectors.toSet()));
        for (ContestRank rank : ranks) {
            UserDisplayInfo user = userMap.get(rank.getUserId());
            // 三级兜底：查不到用户信息 → 用「用户<id>」占位而非留空，
            // 保证导出的行数与名次完整可核对（留空会让该行看起来像数据缺失而非用户问题）
            String name = user == null ? ("用户" + rank.getUserId())
                    : (user.nickname() != null && !user.nickname().isBlank()
                            ? user.nickname() : user.username());
            csv.append(rank.getRankNum()).append(',')
                    .append(escapeCsv(name)).append(',')
                    .append(rank.getSolvedCount() == null ? 0 : rank.getSolvedCount()).append(',')
                    .append(rank.getPenaltyTime() == null ? 0 : rank.getPenaltyTime()).append(',')
                    .append(rank.getTotalScore() == null ? 0 : rank.getTotalScore()).append(',')
                    .append(rank.getRatingBefore() == null ? "" : rank.getRatingBefore()).append(',')
                    .append(rank.getRatingAfter() == null ? "" : rank.getRatingAfter()).append(',')
                    .append(escapeCsv(rank.getAward() == null ? "" : rank.getAward())).append('\n');
        }
        return csv.toString();
    }

    /**
     * 转义 CSV 单元格。
     * <p>
     * 含逗号、引号或换行时用双引号包裹，并把内部引号翻倍。
     * <b>不能省这一步</b>：昵称里出现一个英文逗号就会让整行错列，
     * 这也是导出功能最常见的数据损坏来源。
     * </p>
     */
    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * 判断判题结论是否等价于「通过」。
     * <p>
     * <b>为什么要同时兼容 Number 与 String：</b>submission 的 status 在 oj-api 契约里是
     * {@code Object}——不同历史版本的写入方有的存数字枚举 {@code 1}，有的存字符串 {@code "1"}。
     * 直接 {@code valueOf(status).equals(1)} 会在读到另一种表示时静默判成未通过，
     * 导致重建榜单时所有 AC 记录被当成失败。因此这里做形状无关的宽松判断。
     * </p>
     * <p>
     * 判为通过的依据只有 {@code 1}：改判其它枚举值的含义会静默改变榜单结果，
     * 与其猜测不如让未知的表示保守地按未通过处理。
     * </p>
     *
     * @param status 判题结论，允许为 {@code null}、{@code Number} 或 {@code String}
     * @return 是否等价于通过
     */
    private boolean isAcceptedStatus(Object status) {
        if (status == null) {
            return false;
        }

        if (status instanceof Number) {
            return ((Number) status).intValue() == 1;
        }

        String value = String.valueOf(status).trim();
        if (value.isEmpty()) {
            return false;
        }

        return "1".equals(value);
    }
}
