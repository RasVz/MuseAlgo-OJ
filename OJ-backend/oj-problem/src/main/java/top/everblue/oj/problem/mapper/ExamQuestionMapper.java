package top.everblue.oj.problem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.problem.entity.ExamQuestion;

/**
 * 试卷内题目（客观题题干与选项）Mapper，无手写 SQL。
 * <p>
 * 只存取题目定义本身；判分不在此处做，由
 * {@link top.everblue.oj.problem.domain.ObjectiveJudge} 在提交链路上即时完成，
 * 避免为了查询一道题就把整套作答数据一起拖进来。
 * </p>
 */
@Mapper
public interface ExamQuestionMapper extends BaseMapper<ExamQuestion> {
}
