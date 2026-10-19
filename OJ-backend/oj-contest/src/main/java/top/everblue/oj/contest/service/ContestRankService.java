package top.everblue.oj.contest.service;

import top.everblue.oj.contest.entity.ContestRank;
import top.everblue.oj.contest.entity.vo.ContestRankVO;

import java.util.List;

/**
 * 比赛榜单服务，统管「实时榜」与「最终榜」两条链路。
 * <p>
 * 两个概念必须分清，混淆会导致读错数据：
 * </p>
 * <ul>
 *   <li><b>实时榜</b>：来自数据库提交快照，随已判结果变动，按赛制控制可见性，
 *       消息加速通知，定时任务轮询补送，不依赖缓存完整性。</li>
 *   <li><b>最终榜</b>：来自 contest_rank 表，在比赛结束时一次性固化，之后不再变化，
 *       用于赛后查询与导出。</li>
 * </ul>
 * <p>
 * 因此提供 {@code hasFinalizedRank} 用于判断当前该读哪一份数据，
 * 也用于让「结束比赛」操作具备幂等性：已固化过就不重复计算评分与名次。
 * </p>
 */
public interface ContestRankService {

    /**
     * 比赛结束后固化最终成绩：按数据库快照落库、按名次计算评分并写入完成标记。
     * <p>
     * 应保持<b>幂等</b>——重复调用不得把评分算两遍。
     * </p>
     *
     * @param contestId 比赛 ID
     */
    void finalizeContest(Long contestId);

    /**
     * 读取实时榜前 n 名。
     *
     * @param contestId 比赛 ID
     * @param n 取前几名，{@code n <= 0} 时返回空列表
     * @return 按名次升序、同分并列的榜单；无数据时返回空列表
     */
    List<ContestRankVO> getRealtimeTopN(Long contestId, Integer n);

    /**
     * 反查某选手在实时榜中的名次。
     *
     * @param contestId 比赛 ID
     * @param userId 选手 ID
     * @return 名次（从 1 开始）；选手不在榜上时返回 {@code null}
     */
    Long getRealtimeUserRank(Long contestId, Long userId);

    /**
     * 读取已固化的最终榜。
     *
     * @param contestId 比赛 ID
     * @return 最终成绩行列表；尚未固化时返回空列表
     */
    List<ContestRank> getFinalRankList(Long contestId);

    /**
     * 用历史提交记录重建实时榜（Redis）。
     * <p>
     * 用于 Redis 数据丢失、缓存过期后恢复，或管理员手工清榜后的回填。
     * 这是让实时榜「可重建」而非「只增不减」的关键。
     * </p>
     *
     * @param contestId 比赛 ID
     */
    void rebuildRealtimeRankFromSubmission(Long contestId);

    /**
     * 判断该比赛是否已固化最终榜。
     *
     * @param contestId 比赛 ID
     * @return 已固化返回 {@code true}
     */
    boolean hasFinalizedRank(Long contestId);

    /** 最终成绩 CSV（管理员导出用） */
    String exportCsv(Long contestId);
}
