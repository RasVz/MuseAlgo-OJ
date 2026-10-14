package top.everblue.oj.contest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import top.everblue.oj.contest.entity.ProblemContest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import top.everblue.oj.contest.entity.vo.ContestProblemVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 比赛题目关联 Mapper，本模块定制 SQL 最集中的地方。
 * <p>
 * 几乎每个方法都<b>刻意覆盖</b>了 {@code BaseMapper} 的同名方法，原因是本表有三条通用框架无法表达的约束：
 * </p>
 * <ul>
 *   <li>查询必须排除 {@code is_deleted = 1} 的记录；</li>
 *   <li>列表查询需要联表带出题目标题与难度；</li>
 *   <li>删除是逻辑删除，删除时同时要写 {@code update_time}。</li>
 * </ul>
 * <p>
 * <b>同一 mapper id 不可同时存在注解与 XML 定义</b>：这些方法的实现全部写在
 * 同名 XML 里。若有人「顺手」在接口上加 {@code @Select} 注解来定义同名方法，
 * MyBatis 会以 XML 为准、注解定义被静默忽略（并在日志中打 ERROR），
 * 表现为「加了注解却不生效」，非常难排查。所以本接口一律不写 SQL 注解。
 * </p>
 */
@Mapper
public interface ProblemContestMapper extends BaseMapper<ProblemContest> {

    /**
     * 插入
     */
    int insert(ProblemContest record);

    /**
     * 根据ID查询（排除已删除的）
     * <p>
     * 覆盖 {@code BaseMapper.selectById}：父类实现不会过滤 {@code is_deleted}，
     * 用父类实现会让已移除的题目重新出现在页面上。
     * </p>
     *
     * @param id 关联记录主键
     * @return 记录；不存在或已逻辑删除时返回 {@code null}
     */
    ProblemContest selectById(@Param("id") Long id);

    /**
     * 更新
     */
    int updateById(ProblemContest record);

    /**
     * 逻辑删除
     * <p>
     * 删除的同时写 {@code updateTime}，让「什么时候移出的题目」在数据上可追溯。
     * </p>
     *
     * @param id 关联记录主键
     * @param updateTime 删除时间，由服务层传入以保证与本次事务使用同一时间基准
     * @return 受影响行数
     */
    int logicalDeleteById(@Param("id") Long id, @Param("updateTime") LocalDateTime updateTime);

    /**
     * 修改可见性
     * <p>
     * 只更新 {@code visible} 一列，不触碰其余字段。
     * </p>
     *
     * @param id 关联记录主键
     * @param visible 0-隐藏，1-可见
     * @return 受影响行数
     */
    int updateVisible(@Param("id") Long id, @Param("visible") Integer visible);

    /**
     * 统计数量（用于检查是否存在）
     * <p>
     * <b>这里刻意不过滤 {@code is_deleted}</b>：判断的是「是否曾经配置过这道题」，
     * 包含已逻辑删除的记录。这样把一道移除过的题重新加进来时能被正确识别为重复，
     * 而不是产生第二条配置行导致同一场比赛出现两道同名题。
     * </p>
     *
     * @param contestId 比赛 ID
     * @param problemId 题目 ID
     * @return 匹配的行数
     */
    long countByContestAndProblem(@Param("contestId") Long contestId, @Param("problemId") Long problemId);

    /**
     * 获取某竞赛的最大顺序号
     * <p>
     * 批量加题时用来「续接」已有顺序，避免新题与旧题序号冲突。
     * </p>
     *
     * @param contestId 比赛 ID
     * @return 当前最大序号；该比赛尚无题目时返回 {@code null}（调用方按0 起步处理）
     */
    Integer getMaxOrderByContestId(@Param("contestId") Long contestId);

    /**
     * 查询竞赛题目列表（关联题目详情）
     * <p>
     * {@code visible} 传 {@code null} 表示不按可见性过滤——这是管理端能看到隐藏题的唯一入口，
     * 权限控制完全依赖调用方不把该用法暴露给用户端。
     * </p>
     *
     * @param contestId 比赛 ID
     * @param visible 可见性过滤值，可为 {@code null}
     * @param isDeleted 逻辑删除标记
     * @return 题目配置列表，含题目标题与难度等联表字段
     */
    List<ProblemContest> selectContestProblemsWithDetails(@Param("contestId") Long contestId,
                                                          @Param("visible") Integer visible,
                                                          @Param("isDeleted") Integer isDeleted);

    /**
     * 查询竞赛题目列表，并带出指定用户在每题上的判题状态。
     * <p>
     * {@code userId} 为 {@code null}（未登录）时，SQL 中的状态子查询必须整体失效并返回 {@code NULL}，
     * 否则会退化成「取任意一个用户的提交状态」，把别人的成绩展示给游客。
     * </p>
     *
     * @param contestId 比赛 ID
     * @param visible 可见性过滤值，可为 {@code null}
     * @param userId 当前登录用户 ID，可为 {@code null}
     * @return 题目视图列表
     */
    List<ContestProblemVO> selectContestProblemList(@Param("contestId") Long contestId,
                                                    @Param("visible") Integer visible,
                                                    @Param("userId") Long userId);

    /**
     * 物理删除（清理数据时用）
     * <p>
     * 覆盖 {@code BaseMapper.deleteById}：本表日常删除走逻辑删除，
     * 这里的物理删除仅供数据清理/迁移场景使用，不要用于「把题移出比赛」。
     * </p>
     *
     * @param id 关联记录主键
     * @return 受影响行数
     */
    int deleteById(@Param("id") Long id);
}
