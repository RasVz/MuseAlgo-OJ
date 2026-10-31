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

    @Override
    public List<ProblemVO> selectProblemsWithStatus(Long listId, Long userId) {
        return baseMapper.selectProblemsWithStatus(listId, userId);
    }

    @Override
    public List<Long> selectContainedListIds(Long problemId, Long creatorId) {
        return baseMapper.selectContainedListIds(problemId, creatorId);
    }
}
