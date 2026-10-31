package com.musealgo.oj.contest.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import com.musealgo.oj.contest.mapper.UserRatingMapper;
import java.util.TreeMap;
import java.util.Objects;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.api.submission.ContestSubmission;
import com.musealgo.oj.api.submission.ContestSubmissionQuery;
import com.musealgo.oj.api.user.UserRatingCommand;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.entity.ContestRank;
import com.musealgo.oj.contest.entity.ContestSettlement;
import com.musealgo.oj.contest.entity.ContestSettlementReplay;
import com.musealgo.oj.contest.entity.ContestUser;
import com.musealgo.oj.contest.entity.UserRating;
import com.musealgo.oj.contest.entity.vo.ContestRankVO;
import com.musealgo.oj.contest.entity.vo.SettlementReplayPreviewVO;
import com.musealgo.oj.contest.entity.vo.SettlementReplayResultVO;
import com.musealgo.oj.contest.mapper.ContestMapper;
import com.musealgo.oj.contest.mapper.ContestRankMapper;
import com.musealgo.oj.contest.mapper.ContestSettlementMapper;
import com.musealgo.oj.contest.mapper.ContestSettlementReplayMapper;
import com.musealgo.oj.contest.mapper.ContestUserMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * T8-3 C：从指定已固化比赛起，链式重放后续已固化比赛的榜单与 Rating。
 * <p>
 * 安全边界：仅管理员显式确认后执行；同一事务内完成（失败整体回滚）；最多 50 场 / 20000 行；
 * 存在待判提交、缺少正式选手赛前 Rating 快照时拒绝执行。执行前保存 ranks/流水/结算元数据/当前分的 v2 快照，并通过预览指纹保护恢复；
 * 生产环境执行前仍须做数据库备份并在副本演练。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContestSettlementReplayService {

    public static final int MAX_CONTESTS = 50;
    public static final int MAX_ROWS = 20000;
    private static final String ALGORITHM_VERSION = "v1";
    private static final String RATING_VERSION = "elo-v1";

    private final ContestMapper contestMapper;
    private final ContestRankMapper contestRankMapper;
    private final ContestSettlementMapper settlementMapper;
    private final ContestSettlementReplayMapper replayMapper;
    private final ContestSubmissionQuery submissionQuery;
    private final ContestRankCalculator calculator;
    private final RatingCalculator ratingCalculator;
    private final UserRatingService userRatingService;
    private final UserRatingCommand userRatingCommand;
    private final ContestUserMapper contestUserMapper;
    private final ObjectMapper objectMapper;
    private final UserRatingMapper userRatingMapper;

    @Value("${contest.replay.enabled:false}")
    private boolean enabled;

    /** 版本化可逆快照；提交源也参与指纹，拒绝使用过期预览覆盖新状态。 */
    public record ReplayState(List<Contest> contests, List<ContestRank> ranks,
                              List<UserRating> ratingHistory, List<ContestSettlement> settlements,
                              Map<Long, Integer> userRatings, Map<Long, List<ContestSubmission>> submissions,
                              Map<Long, List<ContestUser>> registrations) { }
    public record ReplayBackup(int version, ReplayState before, String afterFingerprint) { }


    /** 只读预览，返回绑定当前源数据/成绩/Rating 的确认串。 */
    public SettlementReplayPreviewVO preview(Long fromContestId) {
        return previewOf(fromContestId, state(fromContestId), "REPLAY:" + fromContestId + ":");
    }

    @Transactional(rollbackFor = Exception.class)
    public SettlementReplayResultVO execute(Long fromContestId, String reason, Long operatorId, String confirm) {
        requireEnabledAndReason(reason);
        lockChain();
        ReplayState before = state(fromContestId);
        SettlementReplayPreviewVO preview = previewOf(fromContestId, before, "REPLAY:" + fromContestId + ":");
        requireExecutable(preview, confirm);
        LocalDateTime now = LocalDateTime.now();
        ContestSettlementReplay replay = new ContestSettlementReplay();
        replay.setFromContestId(fromContestId); replay.setOperatorId(operatorId); replay.setReason(reason.trim());
        replay.setStatus("RUNNING"); replay.setTotalContests(before.contests().size());
        replay.setAffectedUsers(before.userRatings().size()); replay.setStartedAt(now); replay.setCreateTime(now);
        replay.setBackupJson(json(new ReplayBackup(2, before, null)));
        replayMapper.insert(replay);

        Map<Long, Integer> ratings = baseRatings(before);
        clearResults(before.contests());
        for (Contest contest : before.contests()) replayContest(contest, ratings, now, before);
        userRatingCommand.batchUpdateRatings(ratings);
        replay.setBackupJson(json(new ReplayBackup(2, before, fingerprint(state(fromContestId)))));
        replay.setStatus("COMPLETED"); replay.setFinishedAt(LocalDateTime.now());
        replayMapper.updateById(replay);
        log.info("链式重放完成 from={}, contests={}, replayId={}", fromContestId, before.contests().size(), replay.getId());
        return result(replay);
    }

    /** 列表不含敏感备份；完整备份仅通过管理员详情接口读取。 */
    public List<ContestSettlementReplay> history() {
        return replayMapper.selectList(new LambdaQueryWrapper<ContestSettlementReplay>()
                .orderByDesc(ContestSettlementReplay::getId).last("LIMIT 50")).stream().map(r -> {
                    r.setBackupJson(null); return r;
                }).toList();
    }

    public ContestSettlementReplay getReplay(Long replayId) {
        ContestSettlementReplay replay = replayMapper.selectById(replayId);
        if (replay == null) throw new IllegalArgumentException("重放记录不存在");
        return replay;
    }

    public SettlementReplayPreviewVO previewRestore(Long replayId) {
        ContestSettlementReplay replay = getReplay(replayId);
        ReplayBackup backup = restorableBackup(replay);
        ReplayState current = state(replay.getFromContestId());
        SettlementReplayPreviewVO preview = previewOf(replay.getFromContestId(), current, "RESTORE:" + replayId + ":");
        List<String> warnings = new ArrayList<>(preview.warnings());
        if (!Objects.equals(backup.afterFingerprint(), fingerprint(current))) {
            warnings.add("重放后数据已变化，不能覆盖后续写入；请使用数据库备份恢复流程");
        }
        Long latest = replayMapper.selectList(new LambdaQueryWrapper<ContestSettlementReplay>()
                .eq(ContestSettlementReplay::getStatus, "COMPLETED")
                .orderByDesc(ContestSettlementReplay::getId).last("LIMIT 1")).stream()
                .map(ContestSettlementReplay::getId).findFirst().orElse(null);
        if (!Objects.equals(latest, replayId)) warnings.add("只能恢复最近一条尚未恢复的成功重放");
        return new SettlementReplayPreviewVO(preview.fromContestId(), preview.fromContestTitle(), preview.contests(),
                preview.affectedUsers(), preview.totalOldRows(), warnings, warnings.isEmpty(), preview.confirmation());
    }

    /** 仅恢复最近一次且源数据未改变的成功重放；任一步失败由事务整体回滚。 */
    @Transactional(rollbackFor = Exception.class)
    public SettlementReplayResultVO restore(Long replayId, String reason, Long operatorId, String confirm) {
        requireEnabledAndReason(reason);
        lockChain();
        ContestSettlementReplay replay = getReplay(replayId);
        ReplayBackup backup = restorableBackup(replay);
        requireExecutable(previewRestore(replayId), confirm);
        ReplayState before = backup.before();
        clearResults(before.contests());
        // 保留原主键和时间，保证可逐字段核对，也支持按逆序恢复多次重放。
        before.ranks().forEach(contestRankMapper::insert);
        before.ratingHistory().forEach(userRatingMapper::insert);
        List<Long> ids = before.contests().stream().map(Contest::getId).toList();
        settlementMapper.delete(new LambdaQueryWrapper<ContestSettlement>().in(ContestSettlement::getContestId, ids));
        before.settlements().forEach(settlementMapper::insert);
        userRatingCommand.batchUpdateRatings(before.userRatings());
        replay.setStatus("RESTORED"); replay.setRestoredBy(operatorId); replay.setRestoredAt(LocalDateTime.now());
        replay.setRestoreReason(reason.trim()); replayMapper.updateById(replay);
        return result(replay);
    }

    private ReplayBackup restorableBackup(ContestSettlementReplay replay) {
        if (!"COMPLETED".equals(replay.getStatus())) throw new IllegalStateException("仅成功且未恢复的重放可恢复");
        try {
            ReplayBackup backup = objectMapper.readValue(replay.getBackupJson(), ReplayBackup.class);
            if (backup.version() != 2 || backup.before() == null || backup.afterFingerprint() == null)
                throw new IllegalArgumentException("备份版本不支持恢复");
            return backup;
        } catch (Exception e) {
            throw new IllegalStateException("旧版或不完整备份不能自动恢复，请走数据库恢复流程", e);
        }
    }

    private void requireEnabledAndReason(String reason) {
        if (!enabled) throw new IllegalStateException("链式重放维护开关未开启");
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("必须填写 1~500 字的操作原因");
    }

    private void requireExecutable(SettlementReplayPreviewVO preview, String confirm) {
        if (!preview.canExecute()) throw new IllegalStateException(String.join("；", preview.warnings()));
        if (!Objects.equals(preview.confirmation(), confirm == null ? null : confirm.trim()))
            throw new IllegalStateException("预览已变化或确认串不匹配，请重新预览");
    }

    private void lockChain() {
        if (contestMapper.lockSettlement() == null) throw new IllegalStateException("比赛结算锁记录缺失");
        // 与普通编辑/删除使用相同行锁；全局锁先于比赛锁，和正常结算保持一致。
        contestMapper.selectFinalizedForReplayForUpdate();
    }

    private void clearResults(List<Contest> contests) {
        List<Long> ids = contests.stream().map(Contest::getId).toList();
        userRatingService.deleteByContestIds(ids);
        contestRankMapper.delete(new LambdaQueryWrapper<ContestRank>().in(ContestRank::getContestId, ids));
    }

    private SettlementReplayResultVO result(ContestSettlementReplay replay) {
        return new SettlementReplayResultVO(replay.getId(), replay.getFromContestId(), replay.getTotalContests(),
                replay.getAffectedUsers(), replay.getRestoredAt() == null ? replay.getFinishedAt() : replay.getRestoredAt());
    }

    private void replayContest(Contest contest, Map<Long, Integer> ratings, LocalDateTime now, ReplayState source) {
        List<ContestSubmission> all = source.submissions().get(contest.getId());
        Set<Long> starred = starredUserIds(source.registrations().get(contest.getId()));
        List<ContestSubmission> officialSubs = all.stream()
                .filter(s -> s != null && s.userId() != null && !starred.contains(s.userId())).toList();
        List<ContestSubmission> starredSubs = all.stream()
                .filter(s -> s != null && s.userId() != null && starred.contains(s.userId())).toList();
        List<ContestRankVO> officialRows = calculator.calculate(contest, officialSubs);
        officialRows.forEach(r -> r.setParticipantType("OFFICIAL"));
        List<ContestRankVO> starredRows = calculator.calculate(contest, starredSubs);
        starredRows.forEach(r -> r.setParticipantType("STARRED"));

        if (isRatingApplicable(contest) && !officialRows.isEmpty()) {
            List<Long> ids = officialRows.stream().map(ContestRankVO::getUserId).toList();
            List<Integer> before = ids.stream().map(id -> Objects.requireNonNull(ratings.get(id), "重放用户缺少 Rating 基准")).toList();
            List<Integer> ranks = officialRows.stream().map(ContestRankVO::getRankNum).toList();
            List<UserRating> records = ratingCalculator.calculate(contest.getId(), ids, before, ranks);
            for (int i = 0; i < records.size(); i++) {
                UserRating record = records.get(i);
                record.setCreateTime(contest.getEndTime());
                record.setUpdateTime(LocalDateTime.now());
                ratings.put(ids.get(i), record.getRating());
                officialRows.get(i).setRatingBefore(before.get(i));
                officialRows.get(i).setRatingAfter(record.getRating());
            }
            userRatingService.batchSave(records);
        }

        List<ContestRank> entities = new ArrayList<>();
        for (ContestRankVO row : concat(officialRows, starredRows)) {
            ContestRank entity = new ContestRank();
            entity.setContestId(contest.getId());
            entity.setUserId(row.getUserId());
            entity.setRankNum(row.getRankNum());
            entity.setSolvedCount(row.getSolvedCount());
            entity.setPenaltyTime(row.getPenaltyTime());
            entity.setTotalScore(row.getTotalScore());
            entity.setParticipantType(row.getParticipantType());
            entity.setAlgorithmVersion(ALGORITHM_VERSION);
            entity.setRatingBefore(row.getRatingBefore());
            entity.setRatingAfter(row.getRatingAfter());
            ContestRank previous = source.ranks().stream().filter(r -> r.getContestId().equals(contest.getId())
                    && r.getUserId().equals(row.getUserId())).findFirst().orElse(null);
            entity.setAward(previous == null ? null : previous.getAward());
            entity.setIsVisible(previous == null ? 1 : previous.getIsVisible());
            entity.setIsDeleted(0);
            entity.setCreateTime(now);
            entity.setUpdateTime(now);
            entity.setCreateBy(0L);
            entity.setUpdateBy(0L);
            entities.add(entity);
        }
        if (!entities.isEmpty()) {
            contestRankMapper.batchInsert(entities);
        }

        ContestSettlement settlement = settlementMapper.selectById(contest.getId());
        if (settlement == null) {
            settlement = new ContestSettlement();
            settlement.setContestId(contest.getId());
            settlement.setSettlementNo(2);
            settlement.setFinalizedAt(contest.getRankFinalizedAt() == null ? now : contest.getRankFinalizedAt());
            settlement.setCreateTime(now);
        } else {
            settlement.setSettlementNo((settlement.getSettlementNo() == null ? 1 : settlement.getSettlementNo()) + 1);
        }
        settlement.setAlgorithmVersion(ALGORITHM_VERSION);
        settlement.setRatingAlgorithmVersion(RATING_VERSION);
        settlement.setUpdateTime(now);
        if (settlementMapper.selectById(contest.getId()) == null) {
            settlementMapper.insert(settlement);
        } else {
            settlementMapper.updateById(settlement);
        }
    }

    private List<ContestRankVO> concat(List<ContestRankVO> a, List<ContestRankVO> b) {
        List<ContestRankVO> rows = new ArrayList<>(a);
        rows.addAll(b);
        return rows;
    }

    private Map<Long, Integer> baseRatings(ReplayState state) {
        Map<Long, Integer> base = new HashMap<>();
        for (Contest contest : state.contests()) {
            if (!isRatingApplicable(contest)) continue;
            for (ContestRank row : state.ranks()) {
                if (Objects.equals(contest.getId(), row.getContestId()) && "OFFICIAL".equals(row.getParticipantType()))
                    base.putIfAbsent(row.getUserId(), row.getRatingBefore());
            }
        }
        state.userRatings().forEach(base::putIfAbsent);
        return base;
    }

    private SettlementReplayPreviewVO previewOf(Long fromId, ReplayState state, String prefix) {
        List<String> warnings = new ArrayList<>();
        if (!enabled) warnings.add("链式重放维护开关未开启");
        if (state.contests().size() > MAX_CONTESTS) warnings.add("链长度超过 " + MAX_CONTESTS);
        if (state.ranks().size() > MAX_ROWS) warnings.add("旧成绩行数超过 " + MAX_ROWS);
        Map<Long, Integer> base = new HashMap<>();
        int newRows = 0;
        for (Contest contest : state.contests()) {
            if (submissionQuery.hasPending(contest.getId())) warnings.add("比赛 #" + contest.getId() + " 仍有待判提交");
            Set<Long> starred = starredUserIds(state.registrations().get(contest.getId()));
            List<ContestSubmission> all = state.submissions().get(contest.getId());
            List<ContestRankVO> official = calculator.calculate(contest, all.stream()
                    .filter(s -> !starred.contains(s.userId())).toList());
            newRows += official.size() + calculator.calculate(contest, all.stream()
                    .filter(s -> starred.contains(s.userId())).toList()).size();
            if (!isRatingApplicable(contest)) continue;
            Map<Long, ContestRank> old = state.ranks().stream()
                    .filter(r -> Objects.equals(r.getContestId(), contest.getId()) && "OFFICIAL".equals(r.getParticipantType()))
                    .collect(Collectors.toMap(ContestRank::getUserId, r -> r));
            Set<Long> expected = official.stream().map(ContestRankVO::getUserId).collect(Collectors.toSet());
            if (!expected.equals(old.keySet())) warnings.add("比赛 #" + contest.getId() + " 正式参与者与固化快照不一致");
            for (ContestRank row : old.values()) {
                if (!base.containsKey(row.getUserId())) {
                    if (row.getRatingBefore() == null) warnings.add("用户 #" + row.getUserId() + " 首次正式参赛缺少 Rating 快照");
                    base.put(row.getUserId(), row.getRatingBefore());
                }
            }
        }
        if (newRows > MAX_ROWS) warnings.add("预计新成绩行数超过 " + MAX_ROWS);
        for (ContestSettlement settlement : state.settlements()) {
            if (!ALGORITHM_VERSION.equals(settlement.getAlgorithmVersion()) || !RATING_VERSION.equals(settlement.getRatingAlgorithmVersion()))
                warnings.add("比赛 #" + settlement.getContestId() + " 的算法版本不受支持");
        }
        Set<Long> oldUsers = state.ranks().stream().map(ContestRank::getUserId).collect(Collectors.toSet());
        if (!state.userRatings().keySet().containsAll(oldUsers)) warnings.add("受影响用户不存在，不能安全重放");
        Map<Long, Integer> nos = state.settlements().stream().collect(Collectors.toMap(ContestSettlement::getContestId,
                x -> x.getSettlementNo() == null ? 1 : x.getSettlementNo()));
        List<SettlementReplayPreviewVO.ContestBrief> briefs = state.contests().stream().map(c ->
                new SettlementReplayPreviewVO.ContestBrief(c.getId(), c.getTitle(), c.getEndTime(), nos.get(c.getId()))).toList();
        return new SettlementReplayPreviewVO(fromId, state.contests().get(0).getTitle(), briefs,
                state.userRatings().size(), state.ranks().size(), warnings, warnings.isEmpty(), prefix + fingerprint(state));
    }

    private ReplayState state(Long fromId) {
        List<Contest> all = contestMapper.selectFinalizedForReplay();
        Contest from = all.stream().filter(c -> Objects.equals(c.getId(), fromId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("起点比赛不存在或尚未固化"));
        if (from.getEndTime() == null) throw new IllegalStateException("起点比赛缺少结束时间");
        List<Contest> chain = all.stream().filter(c -> c.getEndTime() != null
                && (c.getEndTime().isAfter(from.getEndTime()) || c.getEndTime().equals(from.getEndTime()) && c.getId() >= fromId)).toList();
        List<Long> ids = chain.stream().map(Contest::getId).toList();
        List<ContestRank> ranks = contestRankMapper.selectList(new LambdaQueryWrapper<ContestRank>()
                .in(ContestRank::getContestId, ids).orderByAsc(ContestRank::getId));
        Set<Long> users = ranks.stream().map(ContestRank::getUserId).collect(Collectors.toSet());
        Map<Long, List<ContestSubmission>> submissions = new TreeMap<>();
        Map<Long, List<ContestUser>> registrations = new TreeMap<>();
        for (Contest c : chain) {
            List<ContestSubmission> subs = submissionQuery.findByContestId(c.getId()).stream()
                    .filter(Objects::nonNull).sorted(Comparator.comparing(ContestSubmission::id)).toList();
            submissions.put(c.getId(), subs);
            registrations.put(c.getId(), contestUserMapper.selectList(new LambdaQueryWrapper<ContestUser>()
                    .eq(ContestUser::getContestId, c.getId()).orderByAsc(ContestUser::getId)));
        }
        return new ReplayState(chain, ranks,
                userRatingMapper.selectList(new LambdaQueryWrapper<UserRating>().in(UserRating::getContestId, ids).orderByAsc(UserRating::getId)),
                settlementMapper.selectList(new LambdaQueryWrapper<ContestSettlement>().in(ContestSettlement::getContestId, ids)
                        .orderByAsc(ContestSettlement::getContestId)),
                new TreeMap<>(userRatingCommand.findRatings(users)), submissions, registrations);
    }

    private Set<Long> starredUserIds(List<ContestUser> registrations) {
        return registrations.stream().filter(r -> Objects.equals(0, r.getEligible()))
                .map(ContestUser::getUserId).collect(Collectors.toSet());
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("重放备份快照生成失败", e); }
    }

    private String fingerprint(ReplayState state) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(state).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("重放预览指纹生成失败", e); }
    }

    private static boolean isRatingApplicable(Contest contest) {
        return !"ENTERTAINMENT".equalsIgnoreCase(contest.getCategory());
    }
}
