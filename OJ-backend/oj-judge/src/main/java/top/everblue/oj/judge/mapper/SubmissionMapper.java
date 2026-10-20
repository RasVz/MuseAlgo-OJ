package top.everblue.oj.judge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import top.everblue.oj.api.profile.ProfileQuery.DimensionSummary;
import top.everblue.oj.api.profile.ProfileQuery.WeekOverview;
import top.everblue.oj.api.profile.TagStat;
import top.everblue.oj.judge.entity.Submission;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Persistence adapter owned by the judge module. */
@Mapper
public interface SubmissionMapper extends BaseMapper<Submission> {

    /** 阶段写入必须与任务行上的状态/代次在同一SQL内校验，不能先查询再无条件更新。 */
    @Update("UPDATE submission s JOIN judge_task t ON t.submission_id = s.id " +
            "SET s.judge_phase = #{phase} WHERE s.id = #{submissionId} AND t.id = #{taskId} " +
            "AND t.status = 'PROCESSING' AND t.attempt_count = #{attempt}")
    int updateOwnedPhase(@Param("submissionId") Long submissionId, @Param("taskId") Long taskId,
                         @Param("attempt") Integer attempt, @Param("phase") String phase);


    @Select("SELECT COUNT(*) FROM submission s JOIN contest c ON c.id=s.contest_id " +
            "WHERE s.contest_id=#{contestId} AND s.vp_attempt_id IS NULL " +
            "AND s.create_time >= c.start_time AND s.create_time <= c.end_time " +
            "AND COALESCE(s.status,0)=0 AND COALESCE(s.judge_phase,'') <> 'FAILED'")
    long countPendingContest(@Param("contestId") Long contestId);

    @Select("SELECT COUNT(*) FROM submission WHERE vp_attempt_id=#{id} " +
            "AND COALESCE(status,0)=0 AND COALESCE(judge_phase,'') <> 'FAILED'")
    long countPendingVp(@Param("id") Long id);

    /** AI 复盘用：按状态统计提交的用例结果（只取计数，不含测试点原文） */
    @Select("SELECT status, COUNT(*) AS cnt FROM submission_case_result " +
            "WHERE submission_id = #{submissionId} GROUP BY status")
    List<Map<String, Object>> selectCaseStatusCounts(@Param("submissionId") Long submissionId);

    /** 管理员用户管理：批量统计提交数 */
    @Select("<script>" +
            "SELECT user_id AS userId, COUNT(*) AS cnt FROM submission " +
            "WHERE user_id IN <foreach collection='userIds' item='uid' open='(' separator=',' close=')'>#{uid}</foreach> " +
            "GROUP BY user_id</script>")
    List<Map<String, Object>> selectSubmissionCountByUsers(@Param("userIds") java.util.Collection<Long> userIds);

    /** VP：时间窗内每题最好成绩（按 contest 归属） */
    @Select("SELECT problem_id AS problemId, MAX(score) AS bestScore FROM submission " +
            "WHERE vp_attempt_id = #{vpAttemptId} AND status BETWEEN 1 AND 5 " +
            "GROUP BY problem_id")
    List<Map<String, Object>> selectVpBestScores(@Param("vpAttemptId") Long vpAttemptId);

    @Select("SELECT id, problem_id, status, create_time FROM submission " +
            "WHERE vp_attempt_id = #{vpAttemptId} AND status BETWEEN 1 AND 5 ORDER BY create_time ASC, id ASC")
    List<Submission> selectVpSubmissions(@Param("vpAttemptId") Long vpAttemptId);

    /** 赛中每题最好状态（AC=1 最小） */
    @Select("SELECT problem_id AS problemId, MIN(status) AS bestStatus FROM submission " +
            "WHERE contest_id = #{contestId} AND user_id = #{userId} " +
            "AND vp_attempt_id IS NULL AND status BETWEEN 1 AND 5 " +
            "GROUP BY problem_id")
    List<Map<String, Object>> selectContestProblemResults(
            @Param("contestId") Long contestId, @Param("userId") Long userId);

    /** 赛后在时间窗内 AC 的题目（订正） */
    @Select("<script>" +
            "SELECT problem_id AS problemId, MIN(create_time) AS acTime FROM submission " +
            "WHERE user_id = #{userId} AND status = 1 " +
            "AND vp_attempt_id IS NULL " +
            "AND problem_id IN <foreach collection='problemIds' item='pid' open='(' separator=',' close=')'>#{pid}</foreach> " +
            "AND create_time >= #{from} AND create_time &lt;= #{to} " +
            "GROUP BY problem_id</script>")
    List<Map<String, Object>> selectPostContestAc(
            @Param("userId") Long userId,
            @Param("problemIds") java.util.Collection<Long> problemIds,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 训练试卷会话用：时间窗内每题最好成绩 */
    @Select("<script>" +
            "SELECT problem_id AS problemId, MAX(score) AS bestScore FROM submission " +
            "WHERE user_id = #{userId} " +
            "AND problem_id IN <foreach collection='problemIds' item='pid' open='(' separator=',' close=')'>#{pid}</foreach> " +
            "AND create_time &gt;= #{from} AND create_time &lt;= #{to} " +
            "GROUP BY problem_id</script>")
    List<Map<String, Object>> selectBestScoresByProblems(
            @Param("userId") Long userId,
            @Param("problemIds") java.util.Collection<Long> problemIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Update("UPDATE submission SET judge_phase = #{phase}, update_time = NOW() WHERE id = #{submissionId}")
    int updateJudgePhase(@Param("submissionId") Long submissionId, @Param("phase") String phase);
    Page<Submission> selectContestSubmissionPage(
            Page<Submission> page,
            @Param("contestId") Long contestId,
            @Param("mineOnly") Boolean mineOnly,
            @Param("currentUserId") Long currentUserId,
            @Param("problemId") Long problemId,
            @Param("language") String language,
            @Param("status") Integer status
    );

    List<Map<String, Object>> selectUserDailySubmissionStats(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    List<Submission> selectAllByContestIdOrderByCreateTimeAsc(@Param("contestId") Long contestId);

    IPage<Map<String, Object>> selectHomeworkSubmissionPage(
            Page<Map<String, Object>> page,
            @Param("classId") Long classId,
            @Param("homeworkId") Long homeworkId,
            @Param("studentId") Long studentId,
            @Param("problemId") Long problemId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    // ---- 画像统计查询 (consumed by ProfileQueryAdapter) ----

    /** 按 tag 聚合：尝试题数、AC 题数、一次 AC 题数、总提交次数、平均 AC 耗时、最高难度 */
    List<TagStat> selectProfileByTag(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /** 按知识域上卷 */
    List<Map<String, Object>> selectProfileByDimension(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /** 周概览：提交数、AC 数、AC 题数（去重）、活跃天数 */
    WeekOverview selectWeekOverview(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /** 学生历史所有 AC 过的题目 id */
    java.util.Set<Long> selectSolvedProblemIds(@Param("userId") Long userId);

    /** 批量聚合多个学生在各知识域的统计（班级百分位用） */
    List<Map<String, Object>> selectClassProfileByDimension(
            @Param("studentIds") java.util.Collection<Long> studentIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    // ---- 班级学情聚合查询 (consumed by ClassInsightService) ----

    /** 班级概览：提交总数、AC 数、活跃学生数、涉及题数 */
    Map<String, Object> selectClassInsightSummary(
            @Param("classId") Long classId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /** 每日提交/AC 趋势 */
    List<Map<String, Object>> selectClassInsightDaily(
            @Param("classId") Long classId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /** 题目维度：提交量降序 */
    List<Map<String, Object>> selectClassInsightProblems(
            @Param("classId") Long classId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /** 学生维度：提交量降序 */
    List<Map<String, Object>> selectClassInsightStudents(
            @Param("classId") Long classId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    // ---- 代码查重查询 (consumed by SubmissionCodeQueryAdapter) ----

    /** 班级内同题提交代码（经 class_student 归属过滤），用于查重比对 */
    @Select("<script>" +
            "SELECT s.id AS submissionId, s.user_id AS userId, s.problem_id AS problemId, " +
            "sc.code AS code, CAST(s.status AS CHAR) AS status, s.create_time AS createTime " +
            "FROM submission s " +
            "INNER JOIN submission_code sc ON sc.submission_id = s.id " +
            "INNER JOIN class_student cs ON cs.student_id = s.user_id AND cs.class_id = #{classId} " +
            "WHERE s.problem_id = #{problemId} AND sc.code IS NOT NULL AND sc.code &lt;&gt; '' " +
            "<if test='since != null'>AND s.create_time &gt;= #{since}</if> " +
            "ORDER BY s.create_time ASC, s.id ASC</script>")
    List<Map<String, Object>> selectClassProblemCodes(
            @Param("classId") Long classId,
            @Param("problemId") Long problemId,
            @Param("since") LocalDateTime since);

    /** 按提交 ID 批量取代码（查重报告展开对比用） */
    @Select("<script>" +
            "SELECT s.id AS submissionId, s.user_id AS userId, s.problem_id AS problemId, " +
            "sc.code AS code, CAST(s.status AS CHAR) AS status, s.create_time AS createTime " +
            "FROM submission s " +
            "INNER JOIN submission_code sc ON sc.submission_id = s.id " +
            "WHERE s.id IN <foreach collection='submissionIds' item='sid' open='(' separator=',' close=')'>#{sid}</foreach> " +
            "ORDER BY s.id ASC</script>")
    List<Map<String, Object>> selectCodesBySubmissionIds(
            @Param("submissionIds") java.util.Collection<Long> submissionIds);

    /** 比赛内同题提交代码（跨班维度），用于查重 M2 比对 */
    @Select("<script>" +
            "SELECT s.id AS submissionId, s.user_id AS userId, s.problem_id AS problemId, " +
            "sc.code AS code, CAST(s.status AS CHAR) AS status, s.create_time AS createTime " +
            "FROM submission s " +
            "INNER JOIN submission_code sc ON sc.submission_id = s.id " +
            "WHERE s.contest_id = #{contestId} AND s.problem_id = #{problemId} " +
            "AND sc.code IS NOT NULL AND sc.code &lt;&gt; '' " +
            "<if test='since != null'>AND s.create_time &gt;= #{since}</if> " +
            "ORDER BY s.create_time ASC, s.id ASC</script>")
    List<Map<String, Object>> selectContestProblemCodes(
            @Param("contestId") Long contestId,
            @Param("problemId") Long problemId,
            @Param("since") LocalDateTime since);

    /** 比赛涉及的全部题目 ID（去重），赛后自动查重枚举题目用 */
    @Select("SELECT DISTINCT problem_id FROM submission s " +
            "INNER JOIN submission_code sc ON sc.submission_id = s.id " +
            "WHERE s.contest_id = #{contestId} AND sc.code IS NOT NULL AND sc.code <> '' " +
            "ORDER BY problem_id ASC")
    List<Long> selectContestProblemIds(@Param("contestId") Long contestId);
}
