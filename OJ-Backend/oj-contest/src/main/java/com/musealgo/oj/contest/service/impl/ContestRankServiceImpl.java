package com.musealgo.oj.contest.service.impl;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.contest.error.ContestErrorCode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.api.submission.ContestSubmission;
import com.musealgo.oj.api.submission.ContestSubmissionQuery;
import com.musealgo.oj.api.user.UserDisplayInfo;
import com.musealgo.oj.api.user.UserQuery;
import com.musealgo.oj.api.user.UserRatingCommand;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.entity.ContestRank;
import com.musealgo.oj.contest.entity.ContestSettlement;
import com.musealgo.oj.contest.entity.ContestUser;
import com.musealgo.oj.contest.entity.UserRating;
import com.musealgo.oj.contest.entity.VpAttempt;
import com.musealgo.oj.contest.entity.vo.ContestRankVO;
import com.musealgo.oj.contest.mapper.ContestMapper;
import com.musealgo.oj.contest.mapper.ContestRankMapper;
import com.musealgo.oj.contest.mapper.ContestSettlementMapper;
import com.musealgo.oj.contest.mapper.ContestUserMapper;
import com.musealgo.oj.contest.mapper.VpAttemptMapper;
import com.musealgo.oj.contest.service.ContestRankCalculator;
import com.musealgo.oj.contest.service.ContestRankService;
import com.musealgo.oj.contest.service.RatingCalculator;
import com.musealgo.oj.contest.service.UserRatingService;
import com.musealgo.oj.contest.sse.ContestRankSsePublisher;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 比赛榜单服务（T10-2）：正式榜只含 OFFICIAL 正式选手；全局榜合并正式/超限打星号/VP。
 * 数据库提交流水是权威来源；VP 不写 contest_rank、不计 Rating。
 */
@Service
@RequiredArgsConstructor
public class ContestRankServiceImpl implements ContestRankService {

    public static final String TYPE_OFFICIAL = "OFFICIAL";
    public static final String TYPE_STARRED = "STARRED";
    public static final String TYPE_VP = "VP";
    /** T8-3 D：当前算法版本；历史成绩保留各自版本，不做静默重算。 */
    public static final String ALGORITHM_VERSION = "v1";
    public static final String RATING_ALGORITHM_VERSION = "elo-v1";

    private final ContestRankMapper contestRankMapper;
    private final ContestMapper contestMapper;
    private final ContestSubmissionQuery contestSubmissionQuery;
    private final ContestRankCalculator calculator;
    private final RatingCalculator ratingCalculator;
    private final UserRatingService userRatingService;
    private final UserQuery userQuery;
    private final UserRatingCommand userRatingCommand;
    private final ContestUserMapper contestUserMapper;
    private final VpAttemptMapper vpAttemptMapper;
    private final ContestSettlementMapper contestSettlementMapper;
    private final ContestRankSsePublisher rankSsePublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finalizeContest(Long contestId) {
        if (contestMapper.lockSettlement() == null) throw new IllegalStateException("比赛结算锁记录缺失");
        Contest contest = contestMapper.selectByIdForUpdate(contestId);
        if (contest == null) throw new BusinessException(ContestErrorCode.CONTEST_NOT_FOUND, "比赛不存在");
        if (contest.getRankFinalizedAt() != null) return;
        if (contest.getEndTime() == null || !contest.getEndTime().isBefore(LocalDateTime.now()))
            throw new BusinessException(ContestErrorCode.CONTEST_NOT_ENDED, "比赛尚未结束");
        if (contestMapper.countEarlierUnsettled(contestId, contest.getEndTime()) > 0)
            throw new BusinessException(ContestErrorCode.CONTEST_EARLIER_SETTLEMENT_PENDING, "更早结束的比赛尚未结算，等待后重试");
        if (contestSubmissionQuery.hasPending(contestId)) throw new BusinessException(ContestErrorCode.CONTEST_JUDGING_PENDING, "仍有提交正在判题，等待后重试");

        List<ContestSubmission> all = contestSubmissionQuery.findByContestId(contestId);
        Set<Long> starred = starredUserIds(contestId);
        List<ContestSubmission> officialSubs = all.stream()
                .filter(s -> s != null && s.userId() != null && !starred.contains(s.userId())).toList();
        List<ContestSubmission> starredSubs = all.stream()
                .filter(s -> s != null && s.userId() != null && starred.contains(s.userId())).toList();
        List<ContestRankVO> officialRows = calculator.calculate(contest, officialSubs);
        officialRows.forEach(r -> r.setParticipantType(TYPE_OFFICIAL));
        List<ContestRankVO> starredRows = calculator.calculate(contest, starredSubs);
        starredRows.forEach(r -> r.setParticipantType(TYPE_STARRED));

        List<ContestRankVO> ratedRows = officialRows;
        var ids = ratedRows.stream().map(ContestRankVO::getUserId).toList();
        var before = ids.isEmpty() ? Map.<Long, Integer>of() : userRatingCommand.findRatings(ids);
        if (isRatingApplicable(contest) && !ratedRows.isEmpty()) {
            var records = ratingCalculator.calculate(contestId, ids,
                    ids.stream().map(id -> before.getOrDefault(id, 1200)).toList(),
                    ratedRows.stream().map(ContestRankVO::getRankNum).toList());
            userRatingService.batchSave(records);
            Map<Long, Integer> updates = records.stream()
                    .collect(Collectors.toMap(UserRating::getUserId, UserRating::getRating));
            userRatingCommand.batchUpdateRatings(updates);
            for (var row : ratedRows) {
                row.setRatingBefore(before.getOrDefault(row.getUserId(), 1200));
                row.setRatingAfter(updates.get(row.getUserId()));
            }
        }

        contestRankMapper.deleteByContestId(contestId);
        List<ContestRankVO> rows = new ArrayList<>(officialRows);
        rows.addAll(starredRows);
        var entities = new ArrayList<ContestRank>();
        var now = LocalDateTime.now();
        for (var row : rows) {
            var entity = new ContestRank();
            BeanUtils.copyProperties(row, entity);
            entity.setAlgorithmVersion(ALGORITHM_VERSION);
            entity.setIsDeleted(0); entity.setCreateBy(0L); entity.setUpdateBy(0L);
            entity.setCreateTime(now); entity.setUpdateTime(now);
            entities.add(entity);
        }
        if (!entities.isEmpty() && contestRankMapper.batchInsert(entities) != entities.size())
            throw new IllegalStateException("比赛成绩保存不完整");
        if (contestMapper.markRankFinalized(contestId) != 1) throw new IllegalStateException("比赛固化标记写入失败");
        ContestSettlement settlement = new ContestSettlement();
        settlement.setContestId(contestId);
        settlement.setAlgorithmVersion(ALGORITHM_VERSION);
        settlement.setRatingAlgorithmVersion(RATING_ALGORITHM_VERSION);
        settlement.setSettlementNo(1);
        settlement.setFinalizedAt(now);
        if (contestSettlementMapper.insert(settlement) != 1) throw new IllegalStateException("比赛结算记录保存失败");
        rankSsePublisher.officialAndGlobalChanged(contestId);
    }

    @Override
    public List<ContestRankVO> getRealtimeTopN(Long contestId, Integer n) {
        return officialSnapshot(contestId).stream().limit(Math.max(0, n == null ? 10 : n)).toList();
    }

    @Override
    public Long getRealtimeUserRank(Long contestId, Long userId) {
        return officialSnapshot(contestId).stream().filter(r -> Objects.equals(r.getUserId(), userId))
                .map(r -> r.getRankNum().longValue()).findFirst().orElse(null);
    }

    private List<ContestRankVO> officialSnapshot(Long contestId) {
        var contest = contestMapper.selectById(contestId);
        if (contest == null || !isRatingApplicable(contest)) return List.of();
        if (contest.getRankFinalizedAt() != null) {
            return getFinalRankList(contestId).stream().map(this::toVO).toList();
        }
        Set<Long> starred = starredUserIds(contestId);
        List<ContestSubmission> subs = contestSubmissionQuery.findByContestId(contestId).stream()
                .filter(s -> s != null && s.userId() != null && !starred.contains(s.userId())).toList();
        List<ContestRankVO> rows = calculator.calculate(contest, subs);
        rows.forEach(r -> r.setParticipantType(TYPE_OFFICIAL));
        return rows;
    }

    @Override
    public List<ContestRank> getFinalRankList(Long contestId) {
        if (contestId == null) return List.of();
        Contest contest = contestMapper.selectById(contestId);
        if (contest == null || !isRatingApplicable(contest)) return List.of();
        return contestRankMapper.selectList(new LambdaQueryWrapper<ContestRank>()
                .eq(ContestRank::getContestId, contestId)
                .eq(ContestRank::getIsDeleted, 0)
                .eq(ContestRank::getParticipantType, TYPE_OFFICIAL)
                .orderByAsc(ContestRank::getRankNum).orderByAsc(ContestRank::getUserId));
    }

    @Override
    public List<ContestRankVO> getGlobalRankList(Long contestId) {
        Contest contest = contestId == null ? null : contestMapper.selectById(contestId);
        if (contest == null) return List.of();
        List<ContestRankVO> rows = new ArrayList<>();
        if (contest.getRankFinalizedAt() != null) {
            contestRankMapper.selectList(new LambdaQueryWrapper<ContestRank>()
                            .eq(ContestRank::getContestId, contestId)
                            .eq(ContestRank::getIsDeleted, 0))
                    .stream().map(this::toVO).forEach(rows::add);
        } else {
            Set<Long> starred = starredUserIds(contestId);
            List<ContestSubmission> subs = contestSubmissionQuery.findByContestId(contestId);
            List<ContestSubmission> officialSubs = subs.stream()
                    .filter(s -> s != null && s.userId() != null && !starred.contains(s.userId())).toList();
            List<ContestSubmission> starredSubs = subs.stream()
                    .filter(s -> s != null && s.userId() != null && starred.contains(s.userId())).toList();
            List<ContestRankVO> officialRows = calculator.calculate(contest, officialSubs);
            officialRows.forEach(r -> r.setParticipantType(TYPE_OFFICIAL));
            List<ContestRankVO> starredRows = calculator.calculate(contest, starredSubs);
            starredRows.forEach(r -> r.setParticipantType(TYPE_STARRED));
            rows.addAll(officialRows);
            rows.addAll(starredRows);
        }
        rows.addAll(vpRows(contest));
        rows.sort(globalComparator(contest));
        for (int i = 0; i < rows.size(); i++) rows.get(i).setRankNum(i + 1);
        return rows;
    }

    private List<ContestRankVO> vpRows(Contest contest) {
        List<VpAttempt> finished = vpAttemptMapper.selectList(new LambdaQueryWrapper<VpAttempt>()
                .eq(VpAttempt::getContestId, contest.getId())
                .eq(VpAttempt::getStatus, VpAttempt.STATUS_FINISHED)
                .orderByAsc(VpAttempt::getId));
        if (finished.isEmpty()) return List.of();
        Map<Long, VpAttempt> latest = new LinkedHashMap<>();
        for (VpAttempt attempt : finished) latest.put(attempt.getUserId(), attempt);
        boolean oi = "OI".equalsIgnoreCase(contest.getMode());
        List<ContestRankVO> rows = new ArrayList<>();
        for (VpAttempt attempt : latest.values()) {
            ContestRankVO vo = new ContestRankVO();
            vo.setContestId(contest.getId());
            vo.setUserId(attempt.getUserId());
            vo.setParticipantType(TYPE_VP);
            vo.setRankNum(0);
            vo.setSolvedCount(oi ? 0 : nz(attempt.getSolvedCount()));
            vo.setPenaltyTime(oi ? 0 : nz(attempt.getPenaltyTime()));
            vo.setTotalScore(oi ? nz(attempt.getScore()) : 0);
            vo.setIsVisible(1);
            rows.add(vo);
        }
        return rows;
    }

    private Comparator<ContestRankVO> globalComparator(Contest contest) {
        boolean oi = "OI".equalsIgnoreCase(contest.getMode());
        Comparator<ContestRankVO> order = oi
                ? Comparator.comparing((ContestRankVO r) -> nz(r.getTotalScore())).reversed()
                : Comparator.comparing((ContestRankVO r) -> nz(r.getSolvedCount())).reversed()
                        .thenComparing(r -> nz(r.getPenaltyTime()));
        return order.thenComparing(r -> r.getUserId() == null ? Long.MAX_VALUE : r.getUserId());
    }

    private ContestRankVO toVO(ContestRank rank) {
        var vo = new ContestRankVO();
        BeanUtils.copyProperties(rank, vo);
        if (vo.getParticipantType() == null) vo.setParticipantType(TYPE_OFFICIAL);
        return vo;
    }

    /** 娱乐赛不计 Rating：正式榜为空，全局榜仍展示选手（无星号/VP 标记者视为普通参赛者）。 */
    private static boolean isRatingApplicable(Contest contest) {
        return contest != null && !"ENTERTAINMENT".equalsIgnoreCase(contest.getCategory());
    }

    private static int nz(Integer value) { return value == null ? 0 : value; }

    @Override public void rebuildRealtimeRankFromSubmission(Long contestId) { officialSnapshot(contestId); }

    @Override
    public boolean hasFinalizedRank(Long contestId) {
        var contest = contestId == null ? null : contestMapper.selectById(contestId);
        return contest != null && contest.getRankFinalizedAt() != null;
    }

    @Override public String exportCsv(Long contestId) { return exportCsv0(contestId, false); }

    @Override public String exportGlobalCsv(Long contestId) { return exportCsv0(contestId, true); }

    private String exportCsv0(Long contestId, boolean global) {
        Contest contest = contestMapper.selectById(contestId);
        if (contest == null || Integer.valueOf(1).equals(contest.getIsDeleted())) {
            throw new BusinessException(ContestErrorCode.CONTEST_NOT_FOUND, "比赛不存在");
        }
        List<ContestRankVO> rows = global ? getGlobalRankList(contestId) : officialSnapshot(contestId);
        StringBuilder csv = new StringBuilder();
        csv.append(global ? "名次,用户,类型,解题数,罚时,总分,Rating前,Rating后,奖项\n"
                : "名次,用户,解题数,罚时,总分,Rating前,Rating后,奖项\n");
        if (rows.isEmpty()) return csv.toString();
        Map<Long, UserDisplayInfo> userMap = userQuery.findDisplayInfoBatch(rows.stream()
                .map(ContestRankVO::getUserId).filter(Objects::nonNull).collect(Collectors.toSet()));
        for (ContestRankVO rank : rows) {
            UserDisplayInfo user = userMap.get(rank.getUserId());
            String name = user == null ? ("用户" + rank.getUserId())
                    : (user.nickname() != null && !user.nickname().isBlank()
                            ? user.nickname() : user.username());
            csv.append(nz(rank.getRankNum())).append(',').append(escapeCsv(name)).append(',');
            if (global) csv.append(escapeCsv(rank.getParticipantType())).append(',');
            csv.append(nz(rank.getSolvedCount())).append(',')
                    .append(nz(rank.getPenaltyTime())).append(',')
                    .append(nz(rank.getTotalScore())).append(',')
                    .append(rank.getRatingBefore() == null ? "" : rank.getRatingBefore()).append(',')
                    .append(rank.getRatingAfter() == null ? "" : rank.getRatingAfter()).append(',')
                    .append(escapeCsv(rank.getAward() == null ? "" : rank.getAward())).append('\n');
        }
        return csv.toString();
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private Set<Long> starredUserIds(Long contestId) {
        List<ContestUser> registrations = contestUserMapper.selectList(
                new LambdaQueryWrapper<ContestUser>()
                        .eq(ContestUser::getContestId, contestId)
                        .eq(ContestUser::getEligible, 0));
        if (registrations == null || registrations.isEmpty()) return Set.of();
        return registrations.stream().map(ContestUser::getUserId).collect(Collectors.toSet());
    }
}
