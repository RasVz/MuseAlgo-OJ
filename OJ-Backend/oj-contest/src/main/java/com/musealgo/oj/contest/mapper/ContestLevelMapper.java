package com.musealgo.oj.contest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.musealgo.oj.contest.entity.ContestLevel;

/** 比赛等级配置 Mapper（T10-1）。 */
@Mapper
public interface ContestLevelMapper extends BaseMapper<ContestLevel> {
}
