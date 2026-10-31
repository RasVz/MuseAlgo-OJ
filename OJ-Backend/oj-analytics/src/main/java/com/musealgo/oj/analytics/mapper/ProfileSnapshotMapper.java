package com.musealgo.oj.analytics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.musealgo.oj.analytics.entity.StudentProfileSnapshot;

import java.time.LocalDate;

/**
 * 学生画像周快照 Mapper。
 * <p>
 * 快照表是<strong>写多读多但写有周期性</strong>的结构：每周定时任务生成一次，
 * 之后被报告生成和画像 API 反复读取。写入统一走 {@link #upsert(StudentProfileSnapshot)} 而不是
 * {@code insert}／{@code saveOrUpdate}——前者依赖 (student_id, period_start) 唯一索引，
 * 任务重跑时不会因为「这周已经生成过」而抛重复键异常或写出两份快照。这是任务可重入的前提。
 * </p>
 * <p>
 * 报告产物是 {@code report_job.html_content} 中的受权 HTML，不再回填
 * {@code report_url}（服务端 PDF/COS 链路未接入，见 docs/ARCHITECTURE.md#十报告任务生命周期）。
 * 该列仅为历史数据保留，读取到旧值时由 {@code saveSnapshot} 原样保留。
 * </p>
 * <p>
 * SQL 定义在 {@code ProfileSnapshotMapper.xml} 中，注意其中的比较运算符需按 XML 规则转义；
 * 同一 mapper id 不要同时再写注解版本。
 * </p>
 */
@Mapper
public interface ProfileSnapshotMapper extends BaseMapper<StudentProfileSnapshot> {

    /** 幂等写入：按 (student_id, period_start) 唯一键冲突时更新 */
    int upsert(StudentProfileSnapshot snapshot);

    /**
     * 把某学生某统计周期的快照标记为过期（T8 重判后调用，见 V56 的 stale 列）。
     * <p>只改标记位、不重建快照：重判事务提交后由监听器触发，避免在事务里做长耗时聚合。</p>
     */
    int markStaleForPeriod(@Param("studentId") Long studentId,
                           @Param("periodStart") LocalDate periodStart);
}