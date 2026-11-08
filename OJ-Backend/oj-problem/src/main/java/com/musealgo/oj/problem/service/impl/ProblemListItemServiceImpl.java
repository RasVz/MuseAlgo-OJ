package com.musealgo.oj.problem.service.impl;

import com.musealgo.oj.problem.entity.ProblemListItem;
import com.musealgo.oj.problem.entity.vo.ProblemVO;
import com.musealgo.oj.problem.mapper.ProblemListItemMapper;
import com.musealgo.oj.problem.service.ProblemListItemService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 题单题目关联 服务实现类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
@Service
public class ProblemListItemServiceImpl extends ServiceImpl<ProblemListItemMapper, ProblemListItem> implements ProblemListItemService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.musealgo.oj.problem.application.ProblemReadAccessService readAccess;

    @Override
    public List<ProblemVO> selectProblemsWithStatus(Long listId, Long userId) {
        return baseMapper.selectProblemsWithStatus(listId, userId).stream().map(problem -> {
            if (problem.getId() == null) return problem; // 保留原 LEFT JOIN 的缺失题目占位。
            if (Integer.valueOf(1).equals(problem.getIsDeleted())) return new ProblemVO();
            if (Boolean.TRUE.equals(problem.getIsPublic())) return problem;
            try { readAccess.requireReadable(problem.getId()); return problem; }
            catch (com.musealgo.oj.common.exception.BusinessException error) {
                if (error.getErrorCode() == com.musealgo.oj.problem.error.ProblemErrorCode.PROBLEM_NOT_FOUND)
                    return new ProblemVO();
                if (error.getErrorCode() == com.musealgo.oj.common.exception.CommonErrorCode.ACCESS_DENIED) return null;
                throw error;
            }
        }).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<Long> selectContainedListIds(Long problemId, Long creatorId) {
        return baseMapper.selectContainedListIds(problemId, creatorId);
    }
}
