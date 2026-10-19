package top.everblue.oj.judge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.judge.entity.SubmissionResult;

/** 一对一标准化结果；重试覆盖由任务代次和完成事务保护。 */
@Mapper
public interface SubmissionResultMapper extends BaseMapper<SubmissionResult> {
    /** 重试/重判复用一对一结果行；由任务代次锁和外层事务保护。 */
    @org.apache.ibatis.annotations.Insert("INSERT INTO submission_result (submission_id,status,score,run_time,run_memory,error_message,judged_at) " +
            "VALUES (#{submissionId},#{status},#{score},#{runTime},#{runMemory},#{errorMessage},#{judgedAt}) " +
            "ON DUPLICATE KEY UPDATE status=VALUES(status),score=VALUES(score),run_time=VALUES(run_time)," +
            "run_memory=VALUES(run_memory),error_message=VALUES(error_message),judged_at=VALUES(judged_at)")
    int upsert(SubmissionResult result);

}
