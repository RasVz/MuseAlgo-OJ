package com.musealgo.oj.contest.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.musealgo.oj.contest.entity.ContestLevel;
import com.musealgo.oj.contest.mapper.ContestLevelMapper;
import com.musealgo.oj.contest.service.ContestLevelService;

import java.util.List;
import java.util.Optional;

/** 比赛等级配置服务实现（T10-1）。 */
@Service
@RequiredArgsConstructor
public class ContestLevelServiceImpl extends ServiceImpl<ContestLevelMapper, ContestLevel> implements ContestLevelService {

    @Override
    public Optional<ContestLevel> findEnabledByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(getOne(new LambdaQueryWrapper<ContestLevel>()
                .eq(ContestLevel::getCode, code)
                .eq(ContestLevel::getEnabled, true)));
    }

    @Override
    public List<ContestLevel> listAll() {
        return list(new LambdaQueryWrapper<ContestLevel>().orderByAsc(ContestLevel::getSortOrder));
    }
}
