package top.everblue.oj.problem.mapper;

import org.apache.ibatis.annotations.Param;
import top.everblue.oj.problem.entity.ProblemListItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import top.everblue.oj.problem.entity.vo.ProblemVO;

import java.util.List;

/**
 * <p>
 * 题单题目关联 Mapper 接口
 * </p>
 * <p>
 * 两个查询方法实现于 {@code resources/mapper/ProblemListItemMapper.xml}；
 * 接口上不要加 {@code @Select}，同名 mapper id 的 XML 定义会让注解静默失效并报 ERROR。
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface ProblemListItemMapper extends BaseMapper<ProblemListItem> {
    List<ProblemVO> selectProblemsWithStatus(@Param("listId") Long listId, @Param("userId") Long userId);

    /**
     * 查询当前用户创建的题单中包含指定题目的题单 id 集合（用于题目详情页「加入题单」标记已加入状态）。
     */
    List<Long> selectContainedListIds(@Param("problemId") Long problemId, @Param("creatorId") Long creatorId);
}
