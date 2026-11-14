package com.musealgo.oj.problem.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.musealgo.oj.problem.entity.Problem;
import com.baomidou.mybatisplus.spring.service.IService;
import com.musealgo.oj.problem.entity.vo.ProblemVO;
import com.musealgo.oj.problem.entity.vo.ProblemFilterOptionsVO;

import java.util.List;

/**
 * <p>
 * 题目表 服务类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface ProblemService extends IService<Problem> {
    Page<ProblemVO> getProblemVOPage(Integer page, Integer size, String keyword, Integer difficulty,
                                     Integer minDifficulty, Integer maxDifficulty, String tag, String tags,
                                     String source, Integer userStatus, Long userId);

    ProblemFilterOptionsVO getProblemFilterOptions();

    List<Problem> getRandomUnsolvedPublicProblems(Long userId, Integer limit);

    /** 比赛进行期需要从题库/搜索/推荐中排除的题目 ID；教师/管理员返回空列表。 */
    List<Long> hiddenContestProblemIds();

    /**
     * 只写标准答案三列（std_answer / std_explanation / std_tested）。
     *
     * @return 是否至少影响一行（false 表示题目不存在）
     */
    boolean updateStd(Long problemId, String stdAnswer, String stdExplanation, Integer stdTested);

    /**
     * 把「标准答案已测试」置 0（无条件 UPDATE，不依赖读快照）。
     *
     * @return 影响行数
     */
    int clearStdTested(Long problemId);
}
