package top.everblue.oj.contest.infrastructure;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.contest.ClassContestPageRequest;
import top.everblue.oj.api.contest.ClassContestQuery;
import top.everblue.oj.api.contest.ClassContestSummary;
import top.everblue.oj.api.submission.PageResult;
import top.everblue.oj.contest.service.ContestService;

import java.util.List;

/**
 * {@link ClassContestQuery} 的 oj-contest 侧实现，把班级维度分页查询包装成只读契约。
 * <p>
 * 字段逐个搬运而非整体属性拷贝，是刻意与实体解耦的做法：
 * 契约接口里的 {@link ClassContestSummary} 是不可变 record，编译期即可保证字段齐全，
 * 而属性拷贝会漏掉新字段且不会报错。
 * </p>
 */
@Service
public class ClassContestQueryAdapter implements ClassContestQuery {

    private final ContestService contestService;

    public ClassContestQueryAdapter(ContestService contestService) {
        this.contestService = contestService;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 排序由 {@link ContestService#getClassContestList} 承担：那里有排序字段白名单，
     * 本适配器原样透传请求参数，不做任何加工，也不引入第二套排序逻辑。
     * </p>
     */
    @Override
    public PageResult<ClassContestSummary> findPage(ClassContestPageRequest request) {
        Page<top.everblue.oj.contest.entity.Contest> page = contestService.getClassContestList(
                request.classId(), request.page(), request.size(), request.keyword(), request.sortField(), request.sortOrder());
        List<ClassContestSummary> records = page.getRecords().stream()
                .map(contest -> new ClassContestSummary(
                        contest.getId(), contest.getTitle(), contest.getDescription(), contest.getStartTime(), contest.getEndTime(),
                        contest.getDuration(), contest.getType(), contest.getAuthorId(), contest.getAuthorName(),
                        contest.getParticipantCount(), contest.getVisible(), contest.getClassId()))
                .toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }
}
