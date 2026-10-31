package com.musealgo.oj.analytics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;
import com.musealgo.oj.analytics.entity.ReportJobAttempt;

@Mapper
public interface ReportJobAttemptMapper extends BaseMapper<ReportJobAttempt> {

    @Insert("INSERT INTO report_job_attempt(job_id, attempt_no, generation_no, trigger_source, actor_id, " +
            "worker_ref, stage, started_at) VALUES(#{jobId}, #{attemptNo}, #{generationNo}, #{triggerSource}, " +
            "#{actorId}, #{workerRef}, 'GENERATING', CURRENT_TIMESTAMP(3))")
    int insertClaim(@Param("jobId") Long jobId, @Param("attemptNo") int attemptNo,
                    @Param("generationNo") long generationNo, @Param("triggerSource") String triggerSource,
                    @Param("actorId") Long actorId, @Param("workerRef") String workerRef);

    @Select("SELECT * FROM report_job_attempt WHERE job_id=#{jobId} AND attempt_no=#{attemptNo} FOR UPDATE")
    ReportJobAttempt selectForUpdate(@Param("jobId") Long jobId, @Param("attemptNo") Integer attemptNo);

    @Update("UPDATE report_job_attempt SET stage='FINALIZING' " +
            "WHERE job_id=#{jobId} AND attempt_no=#{attemptNo} AND generation_no=#{generationNo} " +
            "AND outcome IS NULL")
    int markFinalizing(@Param("jobId") Long jobId, @Param("attemptNo") Integer attemptNo,
                       @Param("generationNo") Long generationNo);

    @Update("UPDATE report_job_attempt SET finished_at=CURRENT_TIMESTAMP(3), outcome=#{outcome}, " +
            "error_code=#{errorCode}, error_message=#{errorMessage} " +
            "WHERE job_id=#{jobId} AND attempt_no=#{attemptNo} AND generation_no=#{generationNo} " +
            "AND outcome IS NULL")
    int finish(@Param("jobId") Long jobId, @Param("attemptNo") Integer attemptNo,
               @Param("generationNo") Long generationNo, @Param("outcome") String outcome,
               @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);

    @Update("UPDATE report_job_attempt SET finished_at=CURRENT_TIMESTAMP(3), outcome='LEASE_EXPIRED', " +
            "error_code=#{errorCode}, error_message=#{safeMessage} WHERE job_id=#{jobId} " +
            "AND attempt_no=#{attemptNo} AND generation_no=#{generationNo} AND outcome IS NULL")
    int finishExpired(@Param("jobId") Long jobId, @Param("attemptNo") Integer attemptNo,
                      @Param("generationNo") Long generationNo, @Param("errorCode") String errorCode,
                      @Param("safeMessage") String safeMessage);
}
