package top.everblue.oj.teaching.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 课件使用情况统计：被几个班级使用。
 * 跨表只读统计（class_courseware），供课件列表与管理端展示。
 */
@Mapper
public interface CoursewareUsageMapper {

    /**
     * 各课件的班级使用数（去重班级）：SELECT courseware_id, COUNT(DISTINCT class_id)
     */
    @Select("<script>"
            + "SELECT courseware_id AS coursewareId, COUNT(DISTINCT class_id) AS cnt "
            + "FROM class_courseware WHERE is_visible = 1 AND courseware_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY courseware_id"
            + "</script>")
    List<Map<String, Object>> countClassUses(@Param("ids") Collection<Long> ids);
}
