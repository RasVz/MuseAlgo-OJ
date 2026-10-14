package top.everblue.oj.problem.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import top.everblue.oj.problem.entity.ProblemList;
import com.baomidou.mybatisplus.spring.service.IService;
import top.everblue.oj.problem.entity.dto.ProblemListOrderDTO;
import top.everblue.oj.problem.entity.vo.ProblemListVO;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 题单表 服务类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface ProblemListService extends IService<ProblemList> {
    IPage<ProblemListVO> getStudentHomeworks(Integer page, Integer size, Long userId);

    Long getUnfinishedCount(Long userId);

    void updateProblemOrder(ProblemListOrderDTO orderDTO);

    Map<String, Object> getHomeworkStatusOverview(Long classId, Long homeworkId);

    /**
     * 通用题单列表分页查询（官方题单/我的题单/作业等）。
     */
    IPage<ProblemListVO> selectVOList(Page<?> page, Integer type, Long classId,
                                      Long creatorId, String keyword, Integer privacy);

    /**
     * 题单统计（按类型聚合）：总数 / 公开数 / 私密数 / 累计收藏。
     * 用于管理中心统计条，避免前端分页求和导致数字失真。
     */
    Map<String, Object> getStats(Integer type);

    /**
     * 题单手动排序：按传入的全量有序 id 列表，将该类型下全部题单 sort_order 重排为 0..n-1。
     */
    void updateSort(Integer type, List<Long> ids);
}
