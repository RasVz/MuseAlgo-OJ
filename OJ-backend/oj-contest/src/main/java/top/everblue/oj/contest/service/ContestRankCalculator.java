package top.everblue.oj.contest.service;

import org.springframework.stereotype.Component;
import top.everblue.oj.api.submission.ContestSubmission;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.entity.vo.ContestRankVO;
import java.time.Duration;
import java.util.*;

/** 同一份数据库快照用于实时榜、消息重放和最终榜；判题完成顺序不影响成绩。 */
@Component
public class ContestRankCalculator {
    public List<ContestRankVO> calculate(Contest contest, List<ContestSubmission> submissions) {
        if (contest == null || contest.getStartTime() == null || contest.getEndTime() == null) return List.of();
        boolean oi = "OI".equalsIgnoreCase(contest.getMode());
        Map<Long, ContestRankVO> users = new HashMap<>();
        Map<Long, Map<Long, Integer>> wrong = new HashMap<>(), best = new HashMap<>();
        Map<Long, Set<Long>> solved = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        submissions.stream().filter(Objects::nonNull)
                .filter(s -> s.id()!=null && s.userId()!=null && s.problemId()!=null && s.submittedAt()!=null)
                .filter(s -> Objects.equals(contest.getId(),s.contestId()) && s.vpAttemptId()==null)
                .filter(s -> !s.submittedAt().isBefore(contest.getStartTime()) && !s.submittedAt().isAfter(contest.getEndTime()))
                .filter(s -> s.status()!=null && s.status()>=1 && s.status()<=5)
                .sorted(Comparator.comparing(ContestSubmission::submittedAt).thenComparing(ContestSubmission::id))
                .forEach(s -> {
                    if (!seen.add(s.id())) return;
                    var vo = users.computeIfAbsent(s.userId(), id -> row(contest.getId(),id));
                    if (oi) {
                        var scores = best.computeIfAbsent(s.userId(), id -> new HashMap<>());
                        int old = scores.getOrDefault(s.problemId(),0);
                        int score = Math.max(0,s.score()==null?0:s.score());
                        if (score>old) { scores.put(s.problemId(),score); vo.setTotalScore(vo.getTotalScore()+score-old); }
                    } else {
                        var passed = solved.computeIfAbsent(s.userId(), id -> new HashSet<>());
                        if (passed.contains(s.problemId())) return;
                        var attempts = wrong.computeIfAbsent(s.userId(), id -> new HashMap<>());
                        if (s.status()==1) {
                            passed.add(s.problemId()); vo.setSolvedCount(vo.getSolvedCount()+1);
                            int minutes = (int)Math.max(0,Duration.between(contest.getStartTime(),s.submittedAt()).toMinutes());
                            vo.setPenaltyTime(vo.getPenaltyTime()+minutes+20*attempts.getOrDefault(s.problemId(),0));
                        } else attempts.merge(s.problemId(),1,Integer::sum);
                    }
                });
        Comparator<ContestRankVO> scoreOrder = oi
                ? Comparator.comparing(ContestRankVO::getTotalScore).reversed()
                : Comparator.comparing(ContestRankVO::getSolvedCount).reversed().thenComparing(ContestRankVO::getPenaltyTime);
        var rows = new ArrayList<>(users.values());
        rows.sort(scoreOrder.thenComparing(ContestRankVO::getUserId));
        for (int i=0;i<rows.size();i++) rows.get(i).setRankNum(i>0 && scoreOrder.compare(rows.get(i-1),rows.get(i))==0
                ? rows.get(i-1).getRankNum() : i+1);
        return rows;
    }
    private ContestRankVO row(Long contestId,Long userId) {
        var row = new ContestRankVO(); row.setContestId(contestId);row.setUserId(userId);
        row.setSolvedCount(0);row.setPenaltyTime(0);row.setTotalScore(0);row.setIsVisible(1);return row;
    }
}
