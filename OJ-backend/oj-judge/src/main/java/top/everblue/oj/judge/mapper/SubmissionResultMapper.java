package top.everblue.oj.judge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.judge.entity.SubmissionResult;

/**
 * 判题结果 Mapper，与 submission 主表 1:1。
 * <p>
 * 与 {@code submission_code} 同属拆表后的附属表：本接口只提供条件查询，
 * 不需要额外方法，因为结果没有多维筛选需求——按 submission_id 取全部即可。
 * </p>
 * <p>
 * 主键是 {@code submission_id}（不是自增 id）：这让「一条提交最多一份结果」由数据库唯一键保证，
 * 判题结果重复写入会直接失败而不是覆盖出两份互相矛盾的记录。
 * 也因此这里不会出现 {@code IdType.AUTO}，改成自增主键就会丢掉这个约束。
 * </p>
 * <p>
 * 本接口刻意保持空：不要为了「统一风格」加 {@code @TableName} 之外的注解或自定义 SQL，
 * 附属表的通用 CRUD 已由 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper} 覆盖。
 * </p>
 */
@Mapper
public interface SubmissionResultMapper extends BaseMapper<SubmissionResult> {
}
