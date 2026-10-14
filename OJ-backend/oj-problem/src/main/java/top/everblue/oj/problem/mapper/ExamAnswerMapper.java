package top.everblue.oj.problem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.problem.entity.ExamAnswer;

/**
 * 学生作答记录 Mapper，无手写 SQL。
 * <p>
 * 作答行随试卷提交批量写入、随试卷删除批量清除，都是整表操作，
 * 因此只继承 {@link BaseMapper} 的通用能力即可，不额外开接口。
 * </p>
 */
@Mapper
public interface ExamAnswerMapper extends BaseMapper<ExamAnswer> {
}
