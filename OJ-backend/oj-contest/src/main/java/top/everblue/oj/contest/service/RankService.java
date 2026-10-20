package top.everblue.oj.contest.service;

import top.everblue.oj.contest.entity.vo.RankVO;

import java.util.List;

/**
 * 全站总排行榜服务（按累计评分排名），对应 {@link RankVO}。
 * <p>
 * 与 {@link ContestRankService} 是两套独立榜单：本接口管「跨比赛累计的荣誉榜」，
 * 名次按行号排、不做并列；后者管「单场比赛内的实时榜与最终榜」，名次支持并列（1,1,3）。
 * 两者均从数据库读取，但名次语义不同。
 * </p>
 */
public interface RankService {
    public List<RankVO> getTopRank(int topN);

    public Long getMyRank(Long userId);
}
