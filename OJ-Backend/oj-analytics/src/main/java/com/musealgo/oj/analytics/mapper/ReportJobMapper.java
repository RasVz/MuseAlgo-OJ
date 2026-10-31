package com.musealgo.oj.analytics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import com.musealgo.oj.analytics.entity.ReportJob;

import java.time.LocalDateTime;
import java.util.List;

/** T9-2 报告任务 Mapper。 */
@Mapper
public interface ReportJobMapper extends BaseMapper<ReportJob> {
    /** 唯一键冲突也取得同一行锁与 ID，不覆盖原任务与尝试次数。 */
    @Insert("INSERT INTO report_job(student_id, student_name, class_id, period_start, period_end, status, attempts, requester_id, active_requester_id, create_time) " +
            "VALUES(#{studentId}, #{studentName}, #{classId}, #{periodStart}, #{periodEnd}, 'PENDING', 0, #{requesterId}, #{activeRequesterId}, CURRENT_TIMESTAMP(3)) " +
            "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOrLock(ReportJob job);

    @Select("SELECT * FROM report_job WHERE id=#{id} FOR UPDATE")
    ReportJob selectForUpdate(Long id);

    @Select("SELECT * FROM report_job WHERE student_id=#{studentId} AND class_id=#{classId} " +
            "AND period_start=#{periodStart} AND period_end=#{periodEnd}")
    ReportJob findByNaturalKey(@Param("studentId") Long studentId, @Param("classId") Long classId,
                               @Param("periodStart") java.time.LocalDate periodStart,
                               @Param("periodEnd") java.time.LocalDate periodEnd);

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    LocalDateTime currentDatabaseTime();

    @Select("SELECT CASE WHEN DATE_ADD(create_time, INTERVAL #{months} MONTH) > CURRENT_TIMESTAMP(3) " +
            "THEN 1 ELSE 0 END FROM report_job WHERE id=#{jobId}")
    boolean isWithinRetention(@Param("jobId") Long jobId, @Param("months") int months);

    // Month-end adjustment in DATE_ADD is not the inverse of DATE_SUB. The conservative
    // three-day bound is indexable; the original condition remains the exact authority.
    @Select("SELECT * FROM report_job FORCE INDEX (ix_report_job_retention) WHERE status IN ('GENERATED', 'FAILED') " +
            "AND create_time<=DATE_ADD(DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL #{months} MONTH), INTERVAL 3 DAY) " +
            "AND DATE_ADD(create_time, INTERVAL #{months} MONTH)<=CURRENT_TIMESTAMP(3) " +
            "ORDER BY id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<ReportJob> selectExpiredTerminalForUpdate(@Param("months") int months, @Param("limit") int limit);

    // Selecting and sorting with FOR UPDATE would lock every qualifying row before LIMIT is
    // applied. Discover by indexed status/due ranges without locks, then lock only one primary
    // key at a time. Keep the former COALESCE ordering and GENERATING-before-PENDING priority.
    @Select("SELECT id, next_attempt_at AS nextAttemptAt, create_time AS createTime " +
            "FROM report_job FORCE INDEX (ix_report_job_expired_lease) " +
            "WHERE status='GENERATING' AND lease_until<=CURRENT_TIMESTAMP(3) " +
            "AND (COALESCE(next_attempt_at,create_time)>#{afterTime} " +
            "OR (COALESCE(next_attempt_at,create_time)=#{afterTime} AND id>#{afterId})) " +
            "ORDER BY COALESCE(next_attempt_at,create_time), id LIMIT #{limit}")
    List<ReportJob> selectExpiredDueCandidates(@Param("afterTime") LocalDateTime afterTime,
                                                @Param("afterId") long afterId, @Param("limit") int limit);

    @Select("SELECT id, next_attempt_at AS nextAttemptAt, create_time AS createTime " +
            "FROM report_job FORCE INDEX (ix_report_job_due) " +
            "WHERE status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=CURRENT_TIMESTAMP(3)) " +
            "AND (COALESCE(next_attempt_at,create_time)>#{afterTime} " +
            "OR (COALESCE(next_attempt_at,create_time)=#{afterTime} AND id>#{afterId})) " +
            "ORDER BY COALESCE(next_attempt_at,create_time), id LIMIT #{limit}")
    List<ReportJob> selectPendingDueCandidates(@Param("afterTime") LocalDateTime afterTime,
                                                @Param("afterId") long afterId, @Param("limit") int limit);

    @Select("SELECT * FROM report_job WHERE id=#{id} AND status='GENERATING' " +
            "AND lease_until<=CURRENT_TIMESTAMP(3) AND COALESCE(next_attempt_at,create_time)=#{orderTime} " +
            "FOR UPDATE SKIP LOCKED")
    ReportJob lockExpiredCandidate(@Param("id") long id, @Param("orderTime") LocalDateTime orderTime);

    @Select("SELECT * FROM report_job WHERE id=#{id} AND status='PENDING' " +
            "AND (next_attempt_at IS NULL OR next_attempt_at<=CURRENT_TIMESTAMP(3)) " +
            "AND COALESCE(next_attempt_at,create_time)=#{orderTime} FOR UPDATE SKIP LOCKED")
    ReportJob lockPendingCandidate(@Param("id") long id, @Param("orderTime") LocalDateTime orderTime);

    default ReportJob selectNextDueForUpdate() {
        ReportJob expired = lockFirstDueCandidate(true);
        return expired != null ? expired : lockFirstDueCandidate(false);
    }

    private ReportJob lockFirstDueCandidate(boolean expired) {
        final int pageSize = 100;
        LocalDateTime afterTime = LocalDateTime.of(1000, 1, 1, 0, 0);
        long afterId = 0;
        while (true) {
            List<ReportJob> candidates = expired
                    ? selectExpiredDueCandidates(afterTime, afterId, pageSize)
                    : selectPendingDueCandidates(afterTime, afterId, pageSize);
            for (ReportJob candidate : candidates) {
                LocalDateTime orderTime = candidate.getNextAttemptAt() == null
                        ? candidate.getCreateTime() : candidate.getNextAttemptAt();
                ReportJob locked = expired ? lockExpiredCandidate(candidate.getId(), orderTime)
                        : lockPendingCandidate(candidate.getId(), orderTime);
                if (locked != null) return locked;
                afterTime = orderTime;
                afterId = candidate.getId();
            }
            if (candidates.size() < pageSize) return null;
        }
    }

    @Update("UPDATE report_job SET status='GENERATING', attempts=#{attemptNo}, generation_no=#{generationNo}, " +
            "lease_owner=#{leaseOwner}, lease_until=LEAST(DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL #{leaseSeconds} SECOND), #{runtimeDeadline}), " +
            "heartbeat_at=CURRENT_TIMESTAMP(3), next_attempt_at=NULL, last_error_code=NULL, error_message=NULL, " +
            "finished_at=NULL, update_time=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND status='PENDING' " +
            "AND (next_attempt_at IS NULL OR next_attempt_at<=CURRENT_TIMESTAMP(3))")
    int claimPending(@Param("id") Long id, @Param("attemptNo") int attemptNo,
                     @Param("generationNo") long generationNo, @Param("leaseOwner") String leaseOwner,
                     @Param("leaseSeconds") int leaseSeconds, @Param("runtimeDeadline") LocalDateTime runtimeDeadline);

    @Update("UPDATE report_job SET lease_until=LEAST(DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL #{leaseSeconds} SECOND), #{runtimeDeadline}), " +
            "heartbeat_at=CURRENT_TIMESTAMP(3), update_time=CURRENT_TIMESTAMP(3) WHERE id=#{id} " +
            "AND status='GENERATING' AND generation_no=#{generationNo} AND lease_owner=#{leaseOwner} " +
            "AND lease_until>CURRENT_TIMESTAMP(3) AND #{runtimeDeadline}>CURRENT_TIMESTAMP(3)")
    int renewLease(@Param("id") Long id, @Param("generationNo") long generationNo,
                   @Param("leaseOwner") String leaseOwner, @Param("leaseSeconds") int leaseSeconds,
                   @Param("runtimeDeadline") LocalDateTime runtimeDeadline);

    @Select("SELECT CASE WHEN COUNT(*)=1 THEN 1 ELSE 0 END FROM report_job WHERE id=#{id} " +
            "AND status='GENERATING' AND generation_no=#{generationNo} AND lease_owner=#{leaseOwner} " +
            "AND lease_until>CURRENT_TIMESTAMP(3) AND #{runtimeDeadline}>CURRENT_TIMESTAMP(3)")
    boolean hasLiveLease(@Param("id") Long id, @Param("generationNo") long generationNo,
                         @Param("leaseOwner") String leaseOwner, @Param("runtimeDeadline") LocalDateTime runtimeDeadline);

    @Update("UPDATE report_job SET html_content=#{html}, snapshot_id=#{snapshotId}, status='GENERATED', " +
            "terminal_revision=terminal_revision+1, last_error_code=NULL, error_message=NULL, " +
            "finished_at=CURRENT_TIMESTAMP(3), lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL, " +
            "next_attempt_at=NULL, update_time=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND status='GENERATING' " +
            "AND generation_no=#{generationNo} AND lease_owner=#{leaseOwner} " +
            "AND lease_until>CURRENT_TIMESTAMP(3) AND #{runtimeDeadline}>CURRENT_TIMESTAMP(3)")
    int finishGenerated(@Param("id") Long id, @Param("generationNo") long generationNo,
                        @Param("leaseOwner") String leaseOwner, @Param("runtimeDeadline") LocalDateTime runtimeDeadline,
                        @Param("snapshotId") Long snapshotId, @Param("html") String html);

    @Update("UPDATE report_job SET status='PENDING', last_error_code=#{errorCode}, error_message=#{safeMessage}, " +
            "next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL #{backoffSeconds} SECOND), " +
            "lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL, finished_at=NULL, update_time=CURRENT_TIMESTAMP(3) " +
            "WHERE id=#{id} AND status='GENERATING' AND generation_no=#{generationNo} AND lease_owner=#{leaseOwner} " +
            "AND lease_until>CURRENT_TIMESTAMP(3) AND #{runtimeDeadline}>CURRENT_TIMESTAMP(3)")
    int returnToPending(@Param("id") Long id, @Param("generationNo") long generationNo,
                        @Param("leaseOwner") String leaseOwner, @Param("runtimeDeadline") LocalDateTime runtimeDeadline,
                        @Param("backoffSeconds") int backoffSeconds, @Param("errorCode") String errorCode,
                        @Param("safeMessage") String safeMessage);

    @Update("UPDATE report_job SET status='FAILED', terminal_revision=terminal_revision+1, " +
            "last_error_code=#{errorCode}, error_message=#{safeMessage}, finished_at=CURRENT_TIMESTAMP(3), " +
            "lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL, next_attempt_at=NULL, " +
            "update_time=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND status='GENERATING' " +
            "AND generation_no=#{generationNo} AND lease_owner=#{leaseOwner} " +
            "AND lease_until>CURRENT_TIMESTAMP(3) AND #{runtimeDeadline}>CURRENT_TIMESTAMP(3)")
    int finishFailed(@Param("id") Long id, @Param("generationNo") long generationNo,
                     @Param("leaseOwner") String leaseOwner, @Param("runtimeDeadline") LocalDateTime runtimeDeadline,
                     @Param("errorCode") String errorCode, @Param("safeMessage") String safeMessage);

    @Update("UPDATE report_job SET status='PENDING', active_requester_id=#{actorId}, last_error_code=NULL, " +
            "error_message=NULL, next_attempt_at=NULL, finished_at=NULL, update_time=CURRENT_TIMESTAMP(3) " +
            "WHERE id=#{id} AND status='FAILED' AND terminal_revision=#{terminalRevision} AND attempts<#{maxAttempts}")
    int retryFailed(@Param("id") Long id, @Param("actorId") Long actorId,
                    @Param("terminalRevision") long terminalRevision, @Param("maxAttempts") int maxAttempts);

    @Select("SELECT * FROM report_job WHERE id=#{id} AND status='GENERATING' AND generation_no=#{generationNo} " +
            "AND lease_owner=#{leaseOwner} AND lease_until<=CURRENT_TIMESTAMP(3) FOR UPDATE")
    ReportJob selectExpiredForUpdate(@Param("id") Long id, @Param("generationNo") long generationNo,
                                     @Param("leaseOwner") String leaseOwner);

    @Update("UPDATE report_job SET status='FAILED', terminal_revision=terminal_revision+1, " +
            "last_error_code=#{errorCode}, error_message=#{safeMessage}, finished_at=CURRENT_TIMESTAMP(3), " +
            "lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL, next_attempt_at=NULL, update_time=CURRENT_TIMESTAMP(3) " +
            "WHERE id=#{id} AND status='GENERATING' AND generation_no=#{generationNo} AND lease_owner=#{leaseOwner} " +
            "AND lease_until<=CURRENT_TIMESTAMP(3) AND attempts>=#{maxAttempts}")
    int finishExpiredFailed(@Param("id") Long id, @Param("generationNo") long generationNo,
                           @Param("leaseOwner") String leaseOwner, @Param("maxAttempts") int maxAttempts,
                           @Param("errorCode") String errorCode, @Param("safeMessage") String safeMessage);

    @Update("UPDATE report_job SET status='FAILED', terminal_revision=terminal_revision+1, " +
            "last_error_code=#{errorCode}, error_message=#{safeMessage}, finished_at=CURRENT_TIMESTAMP(3), " +
            "next_attempt_at=NULL, update_time=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND status='PENDING'")
    int failPendingBeforeClaim(@Param("id") Long id, @Param("errorCode") String errorCode,
                               @Param("safeMessage") String safeMessage);

    @Update("UPDATE report_job SET status='FAILED', terminal_revision=terminal_revision+1, " +
            "last_error_code=#{errorCode}, error_message=#{safeMessage}, finished_at=CURRENT_TIMESTAMP(3), " +
            "next_attempt_at=NULL, update_time=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND status='PENDING' " +
            "AND attempts>=#{maxAttempts} AND (next_attempt_at IS NULL OR next_attempt_at<=CURRENT_TIMESTAMP(3))")
    int failExhaustedPending(@Param("id") Long id, @Param("maxAttempts") int maxAttempts,
                             @Param("errorCode") String errorCode, @Param("safeMessage") String safeMessage);

    @Update("UPDATE report_job SET status='FAILED', terminal_revision=terminal_revision+1, " +
            "last_error_code=#{errorCode}, error_message=#{safeMessage}, finished_at=CURRENT_TIMESTAMP(3), " +
            "lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL, next_attempt_at=NULL, " +
            "update_time=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND status='GENERATING' " +
            "AND generation_no=#{generationNo} AND lease_owner=#{leaseOwner} AND lease_until<=CURRENT_TIMESTAMP(3)")
    int failExpiredBeforeClaim(@Param("id") Long id, @Param("generationNo") long generationNo,
                               @Param("leaseOwner") String leaseOwner, @Param("errorCode") String errorCode,
                               @Param("safeMessage") String safeMessage);

    @Update("UPDATE report_job SET status='PENDING', last_error_code=#{errorCode}, error_message=#{safeMessage}, " +
            "next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL #{backoffSeconds} SECOND), " +
            "lease_owner=NULL, lease_until=NULL, heartbeat_at=NULL, finished_at=NULL, " +
            "update_time=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND status='GENERATING' " +
            "AND generation_no=#{generationNo} AND lease_owner=#{leaseOwner} " +
            "AND lease_until<=CURRENT_TIMESTAMP(3)")
    int requeueExpired(@Param("id") Long id, @Param("generationNo") long generationNo,
                       @Param("leaseOwner") String leaseOwner, @Param("backoffSeconds") int backoffSeconds,
                       @Param("errorCode") String errorCode, @Param("safeMessage") String safeMessage);

    @Update("UPDATE report_job SET status='PENDING', last_error_code='LEGACY_GENERATING_RECOVERED', " +
            "error_message='旧任务已安全回收并排队', next_attempt_at=NULL, lease_owner=NULL, lease_until=NULL, " +
            "heartbeat_at=NULL, finished_at=NULL, update_time=CURRENT_TIMESTAMP(3) " +
            "WHERE id=#{id} AND status='GENERATING' AND lease_until IS NULL AND attempts<#{maxAttempts}")
    int recoverLegacyPending(@Param("id") Long id, @Param("maxAttempts") int maxAttempts);

    @Update("UPDATE report_job SET status='FAILED', terminal_revision=terminal_revision+1, " +
            "last_error_code='ATTEMPTS_EXHAUSTED', error_message='旧任务的生成次数已耗尽', " +
            "finished_at=CURRENT_TIMESTAMP(3), next_attempt_at=NULL, lease_owner=NULL, lease_until=NULL, " +
            "heartbeat_at=NULL, update_time=CURRENT_TIMESTAMP(3) " +
            "WHERE id=#{id} AND status='GENERATING' AND lease_until IS NULL AND attempts>=#{maxAttempts}")
    int recoverLegacyFailed(@Param("id") Long id, @Param("maxAttempts") int maxAttempts);

    @Select("SELECT * FROM report_job WHERE status='GENERATING' AND lease_until IS NULL " +
            "ORDER BY id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<ReportJob> selectLegacyGeneratingForUpdate(@Param("limit") int limit);

    /** Pre-lease rows still owned by the old synchronous worker; read-only release-gate probe. */
    @Select("SELECT COUNT(*) FROM report_job WHERE status='GENERATING' AND lease_until IS NULL")
    int countLegacyGenerating();
}
