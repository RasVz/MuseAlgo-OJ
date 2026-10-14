package top.everblue.oj.analytics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import top.everblue.oj.analytics.entity.PlagiarismReport;

import java.util.Map;

/**
 * 代码查重报告持久化。分页查询在 XML 中 join user/class_student 取双方展示名。
 */
@Mapper
public interface PlagiarismReportMapper extends BaseMapper<PlagiarismReport> {

    /**
     * 查重报告分页（相似度降序），附带双方用户展示名。
     *
     * @param page      分页参数
     * @param classId   班级 ID（可选）
     * @param contestId 比赛 ID（可选，M2）
     * @param problemId 题目 ID（可选）
     * @param status    状态（可选，PENDING/REVIEWED/IGNORED）
     * @return 报告行（含 userAName/userBName 等展示字段）
     */
    IPage<Map<String, Object>> selectReportPage(
            Page<Map<String, Object>> page,
            @Param("classId") Long classId,
            @Param("contestId") Long contestId,
            @Param("problemId") Long problemId,
            @Param("status") String status);
}
