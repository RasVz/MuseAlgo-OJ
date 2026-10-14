package top.everblue.oj.analytics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import top.everblue.oj.analytics.entity.StudentProfileSnapshot;

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
 * 报告 URL 回填单独拆成 {@link #updateReportUrl}：PDF 是异步生成的，
 * 生成完成后只需要补一个字段，如果重跑整条 upsert 会把已算好的画像 JSON 也覆盖掉，
 * 白白重算一轮聚合。
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

    /** 快照已存在时仅回填报告 URL */
    int updateReportUrl(@Param("studentId") Long studentId,
                        @Param("periodStart") LocalDate periodStart,
                        @Param("reportUrl") String reportUrl);
}