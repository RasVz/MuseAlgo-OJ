package top.everblue.oj.problem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.problem.entity.ExamPaperProblem;

/**
 * 试卷-题目关联 Mapper，无手写 SQL。
 * <p>
 * 纯粹的中间表：一行表示"某份卷的第几题用到了某道原题"，用于按题目维度
 * 统计组卷情况与撤销引用。除整表清空外不做单行操作，故无需手写 SQL。
 * </p>
 */
@Mapper
public interface ExamPaperProblemMapper extends BaseMapper<ExamPaperProblem> {
}
