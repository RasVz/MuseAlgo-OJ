package com.musealgo.oj.problem.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.api.problem.ProblemListCommand;
import com.musealgo.oj.problem.entity.ProblemList;
import com.musealgo.oj.problem.service.ProblemListService;

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
        if (!problemListService.save(problemList)) throw new IllegalStateException("班级题单保存失败");
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
        if (!problemListService.updateById(update)) throw new com.musealgo.oj.common.exception.BusinessException(
                com.musealgo.oj.problem.error.ProblemErrorCode.PROBLEM_LIST_NOT_FOUND);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeById(Long id) {
        if (!problemListService.removeById(id)) throw new com.musealgo.oj.common.exception.BusinessException(
                com.musealgo.oj.problem.error.ProblemErrorCode.PROBLEM_LIST_NOT_FOUND);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjustFavoriteCount(Long id, int delta) {
        String expression = delta > 0 ? "favorite_count = favorite_count + 1"
                : "favorite_count = CASE WHEN favorite_count > 0 THEN favorite_count - 1 ELSE 0 END";
        if (!problemListService.update(new LambdaUpdateWrapper<ProblemList>()
                .setSql(expression)
                .eq(ProblemList::getId, id))) throw new IllegalStateException("题单收藏计数更新失败");
    }
}
