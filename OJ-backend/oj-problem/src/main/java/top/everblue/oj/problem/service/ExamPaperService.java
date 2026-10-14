package top.everblue.oj.problem.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import top.everblue.oj.problem.entity.ExamPaper;
import top.everblue.oj.problem.entity.dto.ExamPaperDTO;
import top.everblue.oj.problem.entity.dto.ExamAnswerSubmitDTO;
import top.everblue.oj.problem.entity.vo.ExamPaperVO;
import top.everblue.oj.problem.entity.vo.ExamAttemptVO;
import top.everblue.oj.problem.entity.vo.ExamPaperStatsVO;

import java.util.List;

/**
 * 试卷与作答会话服务。
 * <p>
 * 覆盖两类流程：<b>限时作答</b>（{@code startAttempt} / {@code getAttempt} /
 * {@code finishAttempt} / {@code autoFinalizeExpired}）与
 * <b>试卷管理及统计</b>（组卷、复制、成绩明细、CSV 导出）。
 * </p>
 * <p>
 * 作答会话有一条贯穿全服务的不变量：<b>同一份卷对同一学生至多有一个未结束会话</b>，
 * 且会话一旦过期就必须先落分再返回。任何绕过这一约束的"再开一个会话"实现，
 * 都会让学生在限时考试中拿到两份不同的成绩。
 * </p>
 */
public interface ExamPaperService {

    Page<ExamPaper> listPapers(Integer page, Integer size, String category,
                               String keyword, Boolean publishedOnly);

    ExamPaperVO detail(Long id, boolean withAnswers);

    Long savePaper(ExamPaperDTO dto, Long createBy);

    void deletePaper(Long id);

    /** 提交客观题作答并即时判分，返回每题结果 */
    List<ExamPaperVO.ExamQuestionResultVO> submitAnswers(
            Long paperId, Long userId, ExamAnswerSubmitDTO submit);

    /** 开始限时作答（已有未过期会话则复用；过期会话先自动交卷） */
    ExamAttemptVO startAttempt(Long paperId, Long userId);

    /** 当前会话（过期自动交卷后返回最终结果） */
    ExamAttemptVO getAttempt(Long paperId, Long userId);

    /** 手动交卷 */
    ExamAttemptVO finishAttempt(Long paperId, Long userId);

    /** 定时任务：自动交卷所有已过期会话，返回处理数量 */
    int autoFinalizeExpired();

    /** 复制试卷（深拷贝：基本信息 + 编程题 + 客观题） */
    Long copyPaper(Long paperId, Long createBy);

    /** 试卷成绩统计（作答人数/均值/每题正确率/分数分布） */
    ExamPaperStatsVO paperStats(Long paperId);

    /** 成绩明细（分页，含用户名） */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<ExamPaperVO.ExamAttemptRowVO> listAttempts(
            Long paperId, Integer page, Integer size);

    /** 成绩明细 CSV 文本（B 表头 + 行） */
    String buildAttemptsCsv(Long paperId);
}
