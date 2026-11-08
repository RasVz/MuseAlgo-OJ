package com.musealgo.oj.teaching.mapper;

import com.musealgo.oj.teaching.entity.Class;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 班级表 Mapper 接口
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface ClassMapper extends BaseMapper<Class> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM class WHERE id=#{id} AND is_deleted=0 FOR UPDATE")
    Class selectByIdForUpdate(@Param("id") Long id);
    @org.apache.ibatis.annotations.Update("UPDATE class SET student_count=(SELECT COUNT(*) FROM class_student WHERE class_id=#{id}) WHERE id=#{id} AND is_deleted=0")
    int refreshStudentCount(@Param("id") Long id);
    @org.apache.ibatis.annotations.Select({"<script>SELECT class_id AS classId, COUNT(*) AS memberCount FROM class_student WHERE class_id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "GROUP BY class_id</script>"})
    java.util.List<java.util.Map<String,Object>> selectStudentCounts(@Param("ids") java.util.List<Long> ids);
    IPage<Class> selectJoinedClasses(Page<?> page, @Param("userId") Long userId);
}
