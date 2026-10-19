package top.everblue.oj.judge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import top.everblue.oj.judge.entity.OutboxEvent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 事件 Mapper。
 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    /**
     * 查询待发布的 PENDING 事件（按 next_attempt_at 升序，限制条数）。
     */
    List<OutboxEvent> selectPendingEvents(@Param("limit") int limit);

    /**
     * 查询指定状态下已过归档截止时间的事件（供 OutboxArchiveTask 分页取数）。
     * <p>
     * 时间上界按状态取不同字段（outbox_event 无 updated_at）：
     * <ul>
     *   <li>PUBLISHED → published_at</li>
     *   <li>PENDING（卡死判定）→ next_attempt_at</li>
     *   <li>FAILED → created_at</li>
     * </ul>
     *
     * @param status 事件状态（PUBLISHED / FAILED / PENDING）
     * @param cutoff 截止时间（严格小于）
     * @param limit  单批上限
     */
    List<OutboxEvent> selectExpiredForArchive(@Param("status") String status,
                                              @Param("cutoff") LocalDateTime cutoff,
                                              @Param("limit") int limit);

    /**
     * 将事件插入归档表 outbox_event_archive（archived_at 由数据库默认值填充）。
     */
    int insertArchive(OutboxEvent event);

    /**
     * 按 ID 批量删除原表 outbox_event（搬入归档后调用，须与 insertArchive 同事务）。
     */
    int deleteByIds(@Param("ids") List<String> ids);

    /**
     * 将卡死 PENDING 事件标记为 FAILED（条件更新：仅 PENDING 生效）。
     */
    @Update("UPDATE outbox_event SET status = 'FAILED', last_error = #{error} "
          + "WHERE id = #{id} AND status = 'PENDING'")
    int markStuckAsFailed(@Param("id") String id, @Param("error") String error);

    /**
     * 原子标记事件已发布。
     */
    @Update("UPDATE outbox_event SET status = 'PUBLISHED', published_at = NOW() WHERE id = #{id}")
    int markPublished(@Param("id") String id);

    /**
     * 标记发布失败并更新重试时间和次数。
     */
    // MySQL SET从左到右求值，必须先用旧计数判断，再自增；迟到失败也不能覆盖PUBLISHED。
    @Update("UPDATE outbox_event SET "
          + "status = CASE WHEN retry_count + 1 >= #{maxRetries} THEN 'FAILED' ELSE 'PENDING' END, "
          + "retry_count = retry_count + 1, "
          + "next_attempt_at = DATE_ADD(NOW(), INTERVAL #{backoffSeconds} SECOND), "
          + "last_error = #{error} WHERE id = #{id} AND status = 'PENDING'")
    int markFailed(@Param("id") String id, @Param("error") String error,
                   @Param("backoffSeconds") int backoffSeconds, @Param("maxRetries") int maxRetries);
}
