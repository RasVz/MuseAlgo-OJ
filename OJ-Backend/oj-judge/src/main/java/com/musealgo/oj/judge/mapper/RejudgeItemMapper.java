package com.musealgo.oj.judge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.musealgo.oj.judge.entity.RejudgeItem;

import java.util.Collection;
import java.util.List;

/** 重判条目 Mapper（T8）。 */
@Mapper
public interface RejudgeItemMapper extends BaseMapper<RejudgeItem> {

    /**
     * 查询某提交当前未结束的重判条目（PENDING/RUNNING）。
     * <p>用于重判请求幂等判断，以及判题完成/失败时回写对应条目。</p>
     */
    @Select("SELECT * FROM rejudge_item WHERE submission_id = #{submissionId} "
            + "AND status IN ('PENDING','RUNNING') ORDER BY id DESC LIMIT 1")
    RejudgeItem selectOpenBySubmissionId(@Param("submissionId") Long submissionId);

    /**
     * 批量查询当前有未结束重判条目的提交 ID（批量重判预览/执行排除在途项用，避免 N+1）。
     */
    @Select("<script>"
            + "SELECT DISTINCT submission_id FROM rejudge_item "
            + "WHERE status IN ('PENDING','RUNNING') AND submission_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Long> selectOpenSubmissionIds(@Param("ids") Collection<Long> ids);
}
