package top.everblue.oj.problem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.problem.entity.ExamAttempt;

/**
 * 作答流水 Mapper，无手写 SQL。
 * <p>
 * 每交一次卷产生一条流水，是"这份卷交过了没有"的唯一依据。
 * 状态与超时的判定统一放在应用层，这里不做行数语义之外的加工。
 * </p>
 */
@Mapper
public interface ExamAttemptMapper extends BaseMapper<ExamAttempt> {
}
