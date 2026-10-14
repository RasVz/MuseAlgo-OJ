package top.everblue.oj.judge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.everblue.oj.judge.entity.JudgeTask;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 判题任务 Mapper。
 * <p>
 * 核心方法 {@link #claimTasks(int)} 使用 {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * 实现高并发下的无锁竞争任务领取，保证同一任务只被一个 Worker 获取；
 * 批量领取（LIMIT N）让单轮轮询可并行填充判题线程池（高并发方案 A2）。
 * </p>
 */
@Mapper
public interface JudgeTaskMapper extends BaseMapper<JudgeTask> {

    /** 按状态计数（指标用，如 Prometheus gauge 的 PENDING 队列深度） */
    @Select("SELECT COUNT(*) FROM judge_task WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    /**
     * 原子批量领取待处理的判题任务。
     * <p>
     * 原理：在事务中开启行级排他锁（FOR UPDATE）+ SKIP LOCKED 跳过已被锁定的行。
     * 多个 Worker 同时调用时，MySQL 保证每行最多被一个事务锁定，
     * 其余 Worker 自动跳过，拿到各自不同的行。
     * </p>
     *
     * @param limit 单批上限（与判题线程池容量对齐，避免瞬时领取过量）
     * @return 被领取的任务列表（可能为空，表示当前无待处理任务）
     */
    List<JudgeTask> claimTasks(@Param("limit") int limit);

    /**
     * 查询卡在 PROCESSING 状态超过指定时间的任务（看门狗用）。
     * <p>
     * 只读查询不加锁：看门狗随后会对每条任务走 {@code fail()} 的标准重试流程，
     * 状态迁移由 UPDATE 的乐观语义保证，最坏情况是 Worker 恰好正常完成，重复一次无害。
     * </p>
     *
     * @param deadline 锁定时间早于此时间点的 PROCESSING 任务视为卡住
     * @param limit    单批次上限，避免一次扫描拖垮库
     */
    List<JudgeTask> selectStuckTasks(@Param("deadline") LocalDateTime deadline, @Param("limit") int limit);

    /**
     * 条件完成：仅当任务仍处于 PROCESSING 时才置为 COMPLETED。
     * <p>
     * 防止"看门狗已重置任务、原 Worker 迟到完成"把状态写回 COMPLETED 的竞态。
     * </p>
     *
     * @return 受影响行数（0 = 任务已被看门狗或其他路径迁走，调用方应放弃后续动作）
     */
    int completeIfProcessing(@Param("taskId") Long taskId,
                             @Param("completedAt") LocalDateTime completedAt,
                             @Param("updatedAt") LocalDateTime updatedAt);
}
