package top.everblue.oj.problem.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import top.everblue.oj.problem.entity.Problem;
import com.baomidou.mybatisplus.spring.service.IService;
import top.everblue.oj.problem.entity.vo.ProblemVO;
import top.everblue.oj.problem.entity.vo.ProblemFilterOptionsVO;

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
}
