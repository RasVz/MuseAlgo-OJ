package top.everblue.oj.problem.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.api.problem.ProblemListCommand;
import top.everblue.oj.problem.entity.ProblemList;
import top.everblue.oj.problem.service.ProblemListService;

import java.time.LocalDateTime;

/**
 * 题单写操作契约实现 — oj-problem 侧承载跨模块写请求。
 * <p>
 * 班级题单（oj-teaching）、收藏计数（oj-community）等跨模块写操作统一收口于此，
 * 业务规则（type=3 语义、防篡改字段、计数下限）由数据归属方控制。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ProblemListCommandService implements ProblemListCommand {

    /** 班级题单类型 */
    private static final byte TYPE_CLASS_PROBLEM_LIST = 3;

    private final ProblemListService problemListService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createClassProblemList(NewClassProblemList request) {
        ProblemList problemList = new ProblemList();
        problemList.setClassId(request.classId());
        problemList.setCreatorId(request.creatorId());
        problemList.setTitle(request.title());
        problemList.setDescription(request.description());
        problemList.setType(TYPE_CLASS_PROBLEM_LIST);
        LocalDateTime now = LocalDateTime.now();
        problemList.setCreateTime(now);
        problemList.setUpdateTime(now);
        problemList.setProblemCount(0);
        problemList.setFavoriteCount(0);
        problemList.setPrivacy(0);
        problemListService.save(problemList);
        return problemList.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateClassProblemList(Long id, String title, String description) {
        ProblemList update = new ProblemList();
        update.setId(id);
        update.setTitle(title);
        update.setDescription(description);
        update.setUpdateTime(LocalDateTime.now());
        problemListService.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeById(Long id) {
        problemListService.removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjustFavoriteCount(Long id, int delta) {
        String expression = delta > 0 ? "favorite_count = favorite_count + 1"
                : "favorite_count = CASE WHEN favorite_count > 0 THEN favorite_count - 1 ELSE 0 END";
        problemListService.update(new LambdaUpdateWrapper<ProblemList>()
                .setSql(expression)
                .eq(ProblemList::getId, id));
    }
}
