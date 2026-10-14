package top.everblue.oj.problem.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import top.everblue.oj.problem.entity.ProblemList;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import top.everblue.oj.problem.entity.vo.ProblemListVO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 题单表 Mapper 接口
 * </p>
 * <p>
 * 五个列表类查询（{@code selectStudentHomeworks}、{@code selectVOList}、
 * {@code selectHomeworkStudents}、{@code selectHomeworkProblems}、
 * {@code selectHomeworkCellStats}）实现于
 * {@code resources/mapper/ProblemListMapper.xml}，接口上不要加 {@code @Select}：
 * 同名 mapper id 同时存在 XML 与注解定义时 MyBatis 取 XML、注解静默失效并报 ERROR。
 * </p>
 * <p>
 * <b>时间窗口的开闭性无法从参数名推断，只能看这条 SQL 在做什么。</b>
 * 本模块的 {@code selectHomeworkCellStats} 是<b>闭区间</b> {@code [startTime, endTime]}——
 * 作业按"截止时刻之前提交都算交付"处理，边界包含是刻意的。
 * <b>改成半开会漏掉截止瞬间的提交</b>，不要为了与画像统计"统一口径"而改。
 * </p>
 * <p>
 * 之所以强调"不能凭参数名推断"，是因为全仓时间窗口确实分成两类，但<b>分界线不是参数名</b>：
 * 按分段切片的画像统计（{@code from/to}）一律半开 {@code [from, to)}，相邻段不重不漏；
 * 而 {@code startTime/endTime} 这一组<b>本身就有两种</b>——
 * 本查询与画像模块的 {@code selectHomeworkSubmissionPage} 是闭区间，
 * 而热力图 {@code selectUserDailySubmissionStats} 虽同用 {@code startTime/endTime}
 * 却是半开（按天分桶，跨天边界不能重复计数）。
 * 判断依据只能是<b>该查询的业务语义是"选一个时间段"还是"按时间段切片"</b>。
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface ProblemListMapper extends BaseMapper<ProblemList> {
    IPage<ProblemListVO> selectStudentHomeworks(Page<ProblemListVO> page, @Param("userId") Long userId);

    // 通用列表查询 (支持 官方/个人/作业 等所有类型)
    IPage<ProblemListVO> selectVOList(Page<?> page,
                                      @Param("type") Integer type,
                                      @Param("classId") Long classId,
                                      @Param("creatorId") Long creatorId,
                                      @Param("keyword") String keyword,
                                      @Param("privacy") Integer privacy);

    List<Map<String, Object>> selectHomeworkStudents(@Param("classId") Long classId);

    List<Map<String, Object>> selectHomeworkProblems(@Param("homeworkId") Long homeworkId);

    List<Map<String, Object>> selectHomeworkCellStats(@Param("classId") Long classId,
                                                      @Param("homeworkId") Long homeworkId,
                                                      @Param("startTime") LocalDateTime startTime,
                                                      @Param("endTime") LocalDateTime endTime);
}
