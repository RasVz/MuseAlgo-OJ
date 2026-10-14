package top.everblue.oj.contest.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 赛后订正追踪（每题）。
 * <p>
 * 回答「比赛结束时没做出来的题，赛后一周内补出来了吗」。
 * 这个功能用来评估比赛的区分度：若大量选手赛后都能 AC，说明题太简单或赛中作弊，
 * 区分度不足；反之说明题目难度合理。
 * </p>
 *
 * <p>
 * {@code contestStatus} 是<b>赛中</b>最好状态，{@code corrected} 是<b>赛后</b>状态，
 * 两者组合出的差值才是这个 VO 的意义所在：
 * </p>
 * <ul>
 *   <li>赛中 WA、赛后 {@code corrected=true}：订正成功。</li>
 *   <li>赛中 WA、赛后 {@code corrected=false}：确实不会做。</li>
 *   <li>赛中 AC：无需订正，{@code corrected} 不参与统计。</li>
 * </ul>
 * <p>
 * 注意 {@code contestStatus} 是状态标签（{@code AC}/{@code WA} 等）而非数值，
 * 比较时必须按字符串匹配。
 * </p>
 */
@Data
public class CorrectionVO {
    private Long problemId;
    private String problemTitle;
    private String contestStatus;   // AC/WA/RE/...（赛中最好状态）
    private boolean corrected;      // 赛后 7 天内是否 AC
    private LocalDateTime correctedAt;
}
