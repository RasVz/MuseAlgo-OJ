package top.everblue.oj.problem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.entity.vo.ProblemVO;
import top.everblue.oj.problem.entity.vo.ProblemTagOptionVO;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 题目表 Mapper 接口
 * </p>
 * <p>
 * <b>本接口的 6 个自定义查询（{@code getProblemVOList}、{@code selectPublicKnowledgeTags}、
 * {@code selectPublicTagTexts}、{@code selectPublicSources}、
 * {@code selectRandomUnsolvedPublicProblems}、{@code selectUserStatusByProblemIds}）
 * 全部由 {@code resources/mapper/ProblemMapper.xml} 实现，接口上刻意不写
 * {@code @Select} 注解。</b>MyBatis 对同一个 mapper id 同时存在 XML 定义与注解定义时，
 * 会采用 XML、<b>让注解静默失效并打出 ERROR 日志</b>——不抛异常，所以这种冲突往往到线上
 * 才发现。新增查询请写进 XML，不要图省事加注解。
 * </p>
 * <p>
 * 另外 XML 里写 {@code <} / {@code >} 是<b>必须</b>转义成 {@code &lt;} / {@code &gt;}
 * 或用 CDATA 包住的（XML 按文本解析），这条规则与"注解里不带 {@code <script>} 的原始 SQL 不能转义"恰好相反。
 * </p>
 * <p>
 * 两个容易改错的地方（细节见 XML 内注释）：
 * {@code selectPublicKnowledgeTags} 按 {@code t.name, t.dimension} 分组而非 {@code t.id}，
 * 是为了让筛选选项的粒度是「同名同域」——历史数据里不同 id 同名的标签要合并成一个选项，
 * 否则用户会看到重复项；改回按 id 分组会让重复项回来。
 * {@code getProblemVOList} 里 userStatus 的「输出列」与「筛选条件」是两套写法且<b>不能统一</b>：
 * 输出用 CASE 派生列，筛选用 NOT EXISTS / EXISTS，因为筛选条件要能下推为半连接走索引，
 * 先算成列再过滤就走不了索引了。
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface ProblemMapper extends BaseMapper<Problem> {
    IPage<ProblemVO> getProblemVOList(Page<ProblemVO> page,
                                      @Param("keyword") String keyword,
                                      @Param("difficulty") Integer difficulty,
                                      @Param("minDifficulty") Integer minDifficulty,
                                      @Param("maxDifficulty") Integer maxDifficulty,
                                      @Param("tagNames") List<String> tagNames,
                                      @Param("source") String source,
                                      @Param("userStatus") Integer userStatus,
                                      @Param("userId") Long userId);

    List<ProblemTagOptionVO> selectPublicKnowledgeTags();

    List<String> selectPublicTagTexts();

    List<String> selectPublicSources();

    java.util.List<Problem> selectRandomUnsolvedPublicProblems(@Param("userId") Long userId,
                                                               @Param("limit") Integer limit);

    /**
     * 批量查询用户做题状态（全文搜索回查用）：
     * problemId → 0 未开始 / 1 已通过（存在 AC 提交）/ 2 尝试过未通过。
     * 与 getProblemVOList 的 userStatus 语义保持一致。
     */
    List<Map<String, Object>> selectUserStatusByProblemIds(@Param("userId") Long userId,
                                                           @Param("problemIds") List<Long> problemIds);
}
