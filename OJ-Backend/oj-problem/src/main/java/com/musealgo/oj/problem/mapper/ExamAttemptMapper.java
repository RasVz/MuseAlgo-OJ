package com.musealgo.oj.problem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.musealgo.oj.problem.entity.ExamAttempt;

/**
 * 作答流水 Mapper；读写以 MyBatis-Plus 为主，竞争入口用行锁查询串行化。
 * <p>
 * 每交一次卷产生一条流水，是"这份卷交过了没有"的唯一依据。
 * 状态与超时的判定统一放在应用层，这里不做行数语义之外的加工。
 * </p>
 */
@Mapper
public interface ExamAttemptMapper extends BaseMapper<ExamAttempt> {

    @Select("SELECT * FROM exam_attempt WHERE paper_id = #{paperId} AND user_id = #{userId} " +
            "AND status = 'RUNNING' ORDER BY id DESC LIMIT 1 FOR UPDATE")
    ExamAttempt selectRunningByPaperUserForUpdate(
            @Param("paperId") Long paperId, @Param("userId") Long userId);

    @Select("SELECT * FROM exam_attempt WHERE id = #{id} FOR UPDATE")
    ExamAttempt selectByIdForUpdate(@Param("id") Long id);
}
