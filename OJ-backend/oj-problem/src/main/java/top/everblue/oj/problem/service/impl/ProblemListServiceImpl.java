package top.everblue.oj.problem.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.problem.entity.ProblemList;
import top.everblue.oj.problem.entity.ProblemListItem;
import top.everblue.oj.problem.entity.vo.ProblemListVO;
import top.everblue.oj.problem.mapper.ProblemListItemMapper;
import top.everblue.oj.problem.mapper.ProblemListMapper;
import top.everblue.oj.problem.service.ProblemListService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import top.everblue.oj.problem.entity.dto.ProblemListOrderDTO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * <p>
 * 题单表 服务实现类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
@Service
@RequiredArgsConstructor
public class ProblemListServiceImpl extends ServiceImpl<ProblemListMapper, ProblemList> implements ProblemListService {

    private final ProblemListItemMapper problemListItemMapper;

    @Override
    public IPage<ProblemListVO> getStudentHomeworks(Integer page, Integer size, Long userId) {
        Page<ProblemListVO> pageInfo = new Page<>(page, size);
        return baseMapper.selectStudentHomeworks(pageInfo, userId);
    }

    @Override
    public Long getUnfinishedCount(Long userId) {
        // 1. 复用 Mapper 查出该学生的所有作业
        // 为了统计准确，这里页码设为1，大小设为一个较大的数(比如1000)，确保查出所有正在进行的作业
        // 如果作业非常多，建议专门写一个 count 的 SQL，但目前复用最稳妥
        Page<ProblemListVO> pageParam = new Page<>(1, 1000);
        IPage<ProblemListVO> result = baseMapper.selectStudentHomeworks(pageParam, userId);

        // 2. 过滤：只保留 (已解决数 < 总题目数) 的作业
        long count = result.getRecords().stream()
                .filter(vo -> {
                    // 防空判断
                    int total = vo.getProblemCount() == null ? 0 : vo.getProblemCount();
                    int solved = vo.getSolvedCount() == null ? 0 : vo.getSolvedCount();
                    // 如果已解决小于总数，且总数大于0，则视为待办
                    return solved < total && total > 0;
                })
                .count();

        return count;
    }

    @Override
    public Map<String, Object> getHomeworkStatusOverview(Long classId, Long homeworkId) {
        ProblemList homework = this.getById(homeworkId);
        if (homework == null) {
            return new HashMap<>();
        }

        List<Map<String, Object>> students = baseMapper.selectHomeworkStudents(classId);
        List<Map<String, Object>> problems = baseMapper.selectHomeworkProblems(homeworkId);
        List<Map<String, Object>> stats = baseMapper.selectHomeworkCellStats(
                classId,
                homeworkId,
                homework.getStartTime(),
                homework.getEndTime()
        );

        long totalSubmissions = stats.stream()
                .mapToLong(item -> item.get("submitCount") == null ? 0L : ((Number) item.get("submitCount")).longValue())
                .sum();

        long finalAcCount = stats.stream()
                .filter(item -> item.get("finalAc") != null && ((Number) item.get("finalAc")).intValue() == 1)
                .count();

        long activeStudentCount = stats.stream()
                .map(item -> ((Number) item.get("studentId")).longValue())
                .distinct()
                .count();

        Map<String, Object> homeworkInfo = new HashMap<>();
        homeworkInfo.put("id", homework.getId());
        homeworkInfo.put("title", homework.getTitle());
        homeworkInfo.put("classId", homework.getClassId());
        homeworkInfo.put("startTime", homework.getStartTime());
        homeworkInfo.put("endTime", homework.getEndTime());
        homeworkInfo.put("problemCount", homework.getProblemCount());

        Map<String, Object> summary = new HashMap<>();
        summary.put("studentCount", students.size());
        summary.put("problemCount", problems.size());
        summary.put("totalCellCount", (long) students.size() * problems.size());
        summary.put("totalSubmissions", totalSubmissions);
        summary.put("finalAcCount", finalAcCount);
        summary.put("activeStudentCount", activeStudentCount);

        Map<String, Object> result = new HashMap<>();
        result.put("homework", homeworkInfo);
        result.put("students", students);
        result.put("problems", problems);
        result.put("stats", stats);
        result.put("summary", summary);
        return result;
    }

    /**
     * 更新题目顺序
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProblemOrder(ProblemListOrderDTO orderDTO) {
        Long listId = orderDTO.getListId();
        List<Long> problemIds = orderDTO.getProblemIds();

        if (listId == null || problemIds == null || problemIds.isEmpty()) {
            return;
        }

        // 循环更新每一道题的 sort 值
        // 性能说明：题单题目通常不超过 100 道，循环更新 SQL 开销完全可以接受 (几毫秒)
        for (int i = 0; i < problemIds.size(); i++) {
            Long problemId = problemIds.get(i);
            Integer sort = i; // 下标即为排序值 (0, 1, 2...)

            // 构造更新条件：UPDATE problem_list_item SET sort = ? WHERE list_id = ? AND problem_id = ?
            UpdateWrapper<ProblemListItem> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("list_id", listId)
                    .eq("problem_id", problemId)
                    .set("sort", sort);

            problemListItemMapper.update(null, updateWrapper);
        }
    }

    @Override
    public IPage<ProblemListVO> selectVOList(Page<?> page, Integer type, Long classId,
                                             Long creatorId, String keyword, Integer privacy) {
        return baseMapper.selectVOList(page, type, classId, creatorId, keyword, privacy);
    }

    @Override
    public Map<String, Object> getStats(Integer type) {
        // 官方题单等类型数量有限，直接全量聚合，避免额外 SQL 聚合查询
        List<ProblemList> lists = this.lambdaQuery()
                .eq(type != null, ProblemList::getType, type)
                .list();

        long total = lists.size();
        long publicCount = lists.stream().filter(p -> p.getPrivacy() != null && p.getPrivacy() == 1).count();
        long privateCount = lists.stream().filter(p -> p.getPrivacy() == null || p.getPrivacy() == 0).count();
        long totalFavorite = lists.stream()
                .mapToLong(p -> p.getFavoriteCount() == null ? 0L : p.getFavoriteCount())
                .sum();

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("publicCount", publicCount);
        result.put("privateCount", privateCount);
        result.put("totalFavorite", totalFavorite);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSort(Integer type, List<Long> ids) {
        if (type == null || ids == null || ids.isEmpty()) {
            return;
        }

        // 校验：去重，且全部属于该类型
        List<Long> distinctIds = ids.stream().distinct().toList();
        long belongCount = this.lambdaQuery()
                .eq(ProblemList::getType, type)
                .in(ProblemList::getId, distinctIds)
                .count();
        if (belongCount != distinctIds.size()) {
            return;
        }

        // 该类型全部题单先重置为 0（未参与排序），再按传入顺序写 0..n-1
        this.lambdaUpdate()
                .eq(ProblemList::getType, type)
                .set(ProblemList::getSortOrder, 0)
                .update();

        for (int i = 0; i < distinctIds.size(); i++) {
            this.lambdaUpdate()
                    .eq(ProblemList::getId, distinctIds.get(i))
                    .set(ProblemList::getSortOrder, i)
                    .update();
        }
    }
}
