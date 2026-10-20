package top.everblue.oj.contest.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.api.submission.ContestSubmissionQuery;
import top.everblue.oj.api.user.*;
import top.everblue.oj.contest.entity.*;
import top.everblue.oj.contest.entity.vo.ContestRankVO;
import top.everblue.oj.contest.mapper.*;
import top.everblue.oj.contest.service.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 数据库提交流水是成绩权威来源，MQ/Redis 不参与最终一致性判断。 */
@Service
@RequiredArgsConstructor
public class ContestRankServiceImpl implements ContestRankService {
    private final ContestRankMapper contestRankMapper;
    private final ContestMapper contestMapper;
    private final ContestSubmissionQuery contestSubmissionQuery;
    private final ContestRankCalculator calculator;
    private final RatingCalculator ratingCalculator;
    private final UserRatingService userRatingService;
    private final UserQuery userQuery;
    private final UserRatingCommand userRatingCommand;

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void finalizeContest(Long contestId) {
        if (contestMapper.lockSettlement()==null) throw new IllegalStateException("比赛结算锁记录缺失");
        Contest contest=contestMapper.selectByIdForUpdate(contestId);
        if (contest==null) throw new IllegalArgumentException("比赛不存在");
        if (contest.getRankFinalizedAt()!=null) return;
        if (contest.getEndTime()==null || !contest.getEndTime().isBefore(LocalDateTime.now()))
            throw new IllegalStateException("比赛尚未结束");
        if (contestMapper.countEarlierUnsettled(contestId,contest.getEndTime())>0)
            throw new IllegalStateException("更早结束的比赛尚未结算，等待后重试");
        if (contestSubmissionQuery.hasPending(contestId)) throw new IllegalStateException("仍有提交正在判题，等待后重试");
        var rows=calculator.calculate(contest,contestSubmissionQuery.findByContestId(contestId));
        var ids=rows.stream().map(ContestRankVO::getUserId).toList();
        var before=ids.isEmpty()?Map.<Long,Integer>of():userRatingCommand.findRatings(ids);
        if (!rows.isEmpty()) {
            var records=ratingCalculator.calculate(contestId,ids,ids.stream().map(id->before.getOrDefault(id,1200)).toList(),
                    rows.stream().map(ContestRankVO::getRankNum).toList());
            userRatingService.batchSave(records);
            Map<Long,Integer> updates=records.stream().collect(Collectors.toMap(UserRating::getUserId,UserRating::getRating));
            userRatingCommand.batchUpdateRatings(updates);
            for(var row:rows) {row.setRatingBefore(before.getOrDefault(row.getUserId(),1200));row.setRatingAfter(updates.get(row.getUserId()));}
        }
        contestRankMapper.deleteByContestId(contestId);
        var entities=new ArrayList<ContestRank>();
        var now=LocalDateTime.now();
        for(var row:rows) {
            var entity=new ContestRank();BeanUtils.copyProperties(row,entity);
            entity.setIsDeleted(0);entity.setCreateBy(0L);entity.setUpdateBy(0L);entity.setCreateTime(now);entity.setUpdateTime(now);
            entities.add(entity);
        }
        if(!entities.isEmpty()) contestRankMapper.batchInsert(entities);
        if(contestMapper.markRankFinalized(contestId)!=1) throw new IllegalStateException("比赛固化标记写入失败");
    }
    @Override public List<ContestRankVO> getRealtimeTopN(Long contestId,Integer n) {
        return snapshot(contestId).stream().limit(Math.max(0,n==null?10:n)).toList();
    }
    @Override public Long getRealtimeUserRank(Long contestId,Long userId) {
        return snapshot(contestId).stream().filter(r->Objects.equals(r.getUserId(),userId))
                .map(r->r.getRankNum().longValue()).findFirst().orElse(null);
    }
    private List<ContestRankVO> snapshot(Long contestId) {
        var contest=contestMapper.selectById(contestId);
        if(contest==null) return List.of();
        if(contest.getRankFinalizedAt()!=null) return getFinalRankList(contestId).stream().map(r->{
            var vo=new ContestRankVO();BeanUtils.copyProperties(r,vo);return vo;
        }).toList();
        return calculator.calculate(contest,contestSubmissionQuery.findByContestId(contestId));
    }
    @Override public List<ContestRank> getFinalRankList(Long contestId) {
        if(contestId==null)return List.of();
        return contestRankMapper.selectList(new LambdaQueryWrapper<ContestRank>().eq(ContestRank::getContestId,contestId)
                .eq(ContestRank::getIsDeleted,0).orderByAsc(ContestRank::getRankNum).orderByAsc(ContestRank::getUserId));
    }
    /** 兼容旧管理入口：数据库投影即时重建，无需删除或回放 Redis 增量。 */
    @Override public void rebuildRealtimeRankFromSubmission(Long contestId) { snapshot(contestId); }
    @Override public boolean hasFinalizedRank(Long contestId) {
        var contest=contestId==null?null:contestMapper.selectById(contestId);
        return contest!=null && contest.getRankFinalizedAt()!=null;
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

}
