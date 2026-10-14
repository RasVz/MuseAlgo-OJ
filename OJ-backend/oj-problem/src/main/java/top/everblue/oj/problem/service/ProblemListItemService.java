package top.everblue.oj.problem.service;

import top.everblue.oj.problem.entity.ProblemListItem;
import top.everblue.oj.problem.entity.vo.ProblemVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 题单题目关联 服务类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface ProblemListItemService extends IService<ProblemListItem> {

    /**
     * 获取题单内题目列表（含当前用户提交状态）。
     */
    List<ProblemVO> selectProblemsWithStatus(Long listId, Long userId);

    /**
     * 查询用户创建的题单中包含指定题目的题单 id 集合（题目详情页「加入题单」用）。
     */
    List<Long> selectContainedListIds(Long problemId, Long creatorId);
}
