package top.everblue.oj.contest.service;

import top.everblue.oj.contest.entity.ProblemContest;
import top.everblue.oj.contest.entity.vo.ContestProblemVO;

import java.util.List;

/**
 * 比赛题目配置服务，管理「哪场比赛包含哪些题、每题多少分、是否可见」。
 * <p>
 * 接口按<b>调用方身份</b>分成两套列表查询（{@link #getContestProblems} 用户端 /
 * {@link #getContestProblemsAdmin} 管理端），而不是提供一个带 flag 的方法：
 * 两者的过滤条件不同（用户端必须过滤不可见题），分开能让「管理端不该被用户端调用」
 * 这条约束在类型上就成立，避免漏传 flag 导致隐藏题泄露。
 * </p>
 */
public interface ProblemContestService {

    /**
     * 添加题目到竞赛
     * @param problemContest 关联记录，需带 contestId 与 problemId；分值与顺序可为空，由实现补默认值
     * @return 新增记录的主键 ID
     */
    Long addProblemToContest(ProblemContest problemContest);

    /**
     * 批量添加题目到竞赛
     * @param contestId 比赛 ID
     * @param problemIds 待添加的题目 ID 列表；已存在的题目应跳过而非报错
     * @return 是否至少新增了一条记录
     */
    boolean batchAddProblems(Long contestId, List<Long> problemIds);

    /**
     * 从竞赛中移除题目（逻辑删除）
     * <p>
     * 用逻辑删除而非物理删除：比赛中途换题很常见，物理删除会让「此前是否有人提交过此题」
     * 的追溯线索断掉。
     * </p>
     *
     * @param id contest_problem 关联记录的主键，不是题目 ID
     * @return 是否命中并更新了一条记录
     */
    boolean removeFromContest(Long id);

    /**
     * 更新题目配置
     * <p>
     * 用于调整分值、顺序、可见性；不覆盖题目本身的属性。
     * </p>
     *
     * @param problemContest 含主键 id 与待修改字段
     * @return 是否更新成功
     */
    boolean updateProblemConfig(ProblemContest problemContest);

    /**
     * 切换可见性
     * <p>
     * 用于「渐进式放题」：比赛开始时只显示前几题，临近开赛再放出其余。
     * </p>
     *
     * @param id contest_problem 关联记录主键
     * @param visible 0-隐藏，1-可见
     * @return 是否更新成功
     */
    boolean toggleVisible(Long id, Integer visible);

    /**
     * 获取竞赛题目列表（用户端，只查可见且未删除的）
     * @param contestId 比赛 ID
     * @param visible 可见性过滤值；传 {@code null} 表示不按可见性过滤
     * @return 题目配置列表，按题目顺序升序
     */
    List<ProblemContest> getContestProblems(Long contestId, Integer visible);

    /**
     * 获取竞赛题目列表（管理端，查所有未删除的）
     * @param contestId 比赛 ID
     * @return 题目配置列表，含隐藏题，按题目顺序升序
     */
    List<ProblemContest> getContestProblemsAdmin(Long contestId);

    /**
     * 根据ID查询
     * @param id contest_problem 关联记录主键
     * @return 记录；不存在时返回 {@code null}
     */
    ProblemContest getById(Long id);

    /**
     * 检查是否存在
     * <p>
     * 注意这是「是否配置过」而非「是否未删除」——用于新增前的防重判断。
     * </p>
     *
     * @param contestId 比赛 ID
     * @param problemId 题目 ID
     * @return 已配置返回 {@code true}
     */
    boolean checkExist(Long contestId, Long problemId);

    /**
     * 竞赛题目列表（含用户提交状态）。
     * <p>
     * 与 {@link #getContestProblems} 的差别是多带出 {@code status}（该用户对本题的判题结论），
     * 需要按用户联查 submission。{@code userId} 为空（未登录）时该字段应为 {@code null}，
     * 不得沿用其他用户的状态。
     * </p>
     *
     * @param contestId 比赛 ID
     * @param visible 可见性过滤值；{@code null} 表示不过滤
     * @param userId 当前登录用户 ID，可为 {@code null}
     * @return 题目视图列表
     */
    List<ContestProblemVO> selectContestProblemList(Long contestId, Integer visible, Long userId);
}
