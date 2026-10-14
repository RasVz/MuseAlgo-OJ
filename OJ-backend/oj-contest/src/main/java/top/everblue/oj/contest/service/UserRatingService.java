package top.everblue.oj.contest.service;

import top.everblue.oj.contest.entity.UserRating;
import top.everblue.oj.contest.entity.vo.UserRatingVO;

import java.util.List;

/**
 * 选手评分流水的读写服务。
 * <p>
 * 只管「已经算好的评分变化」的存取；具体怎么算由 {@link RatingCalculator} 负责。
 * 这条边界是刻意的：算法（可替换、可调参）与存储（可独立演进）分开，
 * 更换评分算法不会牵动流水表的读写路径。
 * </p>
 */
public interface UserRatingService {

    /**
     * 查询某选手的完整评分历史，按时间升序，直接对应折线图的X/Y 轴。
     *
     * @param userId 选手 ID
     * @return 评分流水列表，无记录时返回空列表
     */
    List<UserRatingVO> getRatingHistory(Long userId);

    /** 批量保存 rating 记录
     * <p>
     * 必须是批量的：一场比赛结束要为所有参赛者各写一条，逐条插入在数百人规模下
     * 往返次数过多。调用方需保证整批数据同成功同失败，避免半批写入造成评分曲线断层。
     * </p>
     *
     * @param ratings 待保存的评分记录
     */
    void batchSave(List<UserRating> ratings);
}
