package top.everblue.oj.analytics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.analytics.entity.Tag;

import java.util.List;

/**
 * 标签字典 Mapper，负责三层知识图谱的读取入口。
 * <p>
 * {@link #selectDimensions()} 与普通的 {@code selectList} 不同：知识域（parent_id 为空的顶层节点）
 * 是雷达图的固定维度，<strong>顺序即展示顺序</strong>，所以必须显式按 {@code sort_order} 排序，
 * 不能依赖数据库的默认返回顺序。任意地方改用 {@code selectList} 代替都会让六维雷达的轴序随机跳动。
 * </p>
 * <p>
 * 子标签的批量读取走 {@code selectBatchIds} / 条件查询，不在本接口单独定义——它们没有额外的
 * 排序或过滤语义，额外包一层只会让调用点变多。
 * </p>
 * <p>
 * {@link #selectDimensions()} 的 SQL 定义在 XML 中，同一 mapper id 不要再加注解版本，
 * 否则 MyBatis 会取 XML 并对注解打 ERROR。
 * </p>
 */
@Mapper
public interface TagMapper extends BaseMapper<Tag> {

    /**
     * 查询所有知识域（parent_id IS NULL），按 sort_order 排序。
     * 用于雷达图维度。
     */
    List<Tag> selectDimensions();
}