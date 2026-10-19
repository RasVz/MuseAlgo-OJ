package top.everblue.oj.contest.service;

import top.everblue.oj.contest.entity.vo.CorrectionVO;
import top.everblue.oj.contest.entity.vo.VpAttemptVO;

import java.util.List;

/**
 * 虚拟赛（VP）与赛后订正追踪服务。
 * <p>
 * 两者放在一起是因为它们共享同一类数据来源（都以contestId + userId 为定位键，
 * 都要跨到 submission 查询），拆成两个服务只会让调用方反复传同样的两个参数。
 * </p>
 * <p>
 * VP 的成绩<b>不写入正式榜单</b>，只落在 vp_attempt 表内；
 * 这一点由消息消费者识别 vpAttemptId 后跳过正式榜来保证。
 * </p>
 */
public interface VpAttemptService {

    /**
     * 开始一次虚拟赛，创建计时中的会话。
     * <p>
     * 需对同一 (contestId, userId) 串行化，避免重复点击产生两个并行会话。
     * </p>
     *
     * @param contestId 比赛 ID
     * @param userId 发起 VP 的用户 ID
     * @return 新建或已存在的会话视图，含剩余秒数
     * @throws IllegalStateException 对应关系不存在等前置条件不满足时
     */
    VpAttemptVO start(Long contestId, Long userId);

    /**
     * 交卷停止接收提交；有在途判题时先返回SETTLING，完成后冻结成绩。
     *
     * @param contestId 比赛 ID
     * @param userId 用户 ID
     * @return 冻结后的会话视图
     * @throws IllegalStateException 该用户没有进行中的 VP 会话时
     */
    VpAttemptVO finish(Long contestId, Long userId);

    /**
     * 查询用户在该场比赛下的 VP 会话。
     *
     * @param contestId 比赛 ID
     * @param userId 用户 ID
     * @return 会话视图；从未发起过 VP 时返回 {@code null}
     */
    VpAttemptVO getMy(Long contestId, Long userId);

    /** 赛后 7 天订正追踪
     * <p>
     * 回答「比赛结束时没做出来的题，赛后 7 天内是否补做出来了」。
     * 注意与 {@link #start} 不同：订正追踪<b>不要求发起过 VP</b>，
     * 只要参加或查看过这场比赛即可查询。
     * </p>
     *
     * @param contestId 比赛 ID
     * @param userId 用户 ID
     * @return 该用户在本场比赛每道题上的赛中最好状态与赛后订正情况
     */
    List<CorrectionVO> correction(Long contestId, Long userId);
}
