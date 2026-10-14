package top.everblue.oj.problem.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.problem.ProblemListQuery;
import top.everblue.oj.api.problem.ProblemListView;
import top.everblue.oj.api.submission.PageResult;
import top.everblue.oj.problem.entity.ProblemList;
import top.everblue.oj.problem.service.ProblemListService;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 题单只读查询契约适配器 — oj-problem 侧实现 {@link ProblemListQuery}。
 * <p>
 * 对外只暴露 {@link ProblemListView} 只读投射，消费者不接触实体。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ProblemListQueryAdapter implements ProblemListQuery {

    private final ProblemListService problemListService;

    @Override
    public Optional<ProblemListView> findById(Long problemListId) {
        if (problemListId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(problemListService.getById(problemListId)).map(this::toView);
    }

    @Override
    public List<ProblemListView> findByClassIds(Collection<Long> classIds) {
        if (classIds == null || classIds.isEmpty()) {
            return Collections.emptyList();
        }
        return problemListService.list(new LambdaQueryWrapper<ProblemList>()
                        .in(ProblemList::getClassId, classIds)
                        .orderByDesc(ProblemList::getCreateTime))
                .stream().map(this::toView).toList();
    }

    @Override
    public PageResult<ProblemListView> findClassProblemListPage(Long classId, int page, int size) {
        Page<ProblemList> raw = problemListService.page(new Page<>(page, size),
                new LambdaQueryWrapper<ProblemList>()
                        .eq(ProblemList::getClassId, classId)
                        .eq(ProblemList::getType, 3)
                        .orderByDesc(ProblemList::getCreateTime));
        List<ProblemListView> records = raw.getRecords().stream().map(this::toView).toList();
        return new PageResult<>(raw.getCurrent(), raw.getSize(), raw.getTotal(), records);
    }

    @Override
    public PageResult<ProblemListView> findPageByIds(Collection<Long> ids, String keyword, int page, int size) {
        Page<ProblemList> raw = new Page<>(page, size);
        if (ids == null || ids.isEmpty()) {
            return new PageResult<>(raw.getCurrent(), raw.getSize(), 0, Collections.emptyList());
        }
        LambdaQueryWrapper<ProblemList> query = new LambdaQueryWrapper<ProblemList>()
                .in(ProblemList::getId, ids);
        if (keyword != null && !keyword.trim().isEmpty()) {
            query.like(ProblemList::getTitle, keyword.trim());
        }
        IPage<ProblemList> result = problemListService.page(raw, query);
        List<ProblemListView> records = result.getRecords().stream().map(this::toView).toList();
        return new PageResult<>(result.getCurrent(), result.getSize(), result.getTotal(), records);
    }

    @Override
    public Map<String, Object> getHomeworkStatusOverview(Long classId, Long homeworkId) {
        return problemListService.getHomeworkStatusOverview(classId, homeworkId);
    }

    private ProblemListView toView(ProblemList pl) {
        return new ProblemListView(
                pl.getId(),
                pl.getTitle(),
                pl.getDescription(),
                pl.getType(),
                pl.getClassId(),
                pl.getCreatorId(),
                pl.getProblemCount(),
                pl.getFavoriteCount(),
                pl.getPrivacy(),
                pl.getStartTime(),
                pl.getEndTime(),
                pl.getCreateTime(),
                pl.getUpdateTime()
        );
    }
}
