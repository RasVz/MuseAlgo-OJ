package com.musealgo.oj.contest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.musealgo.oj.contest.entity.ContestSettlementReplay;

/** T8-3 C：结算链式重放审计 Mapper。 */
@Mapper
public interface ContestSettlementReplayMapper extends BaseMapper<ContestSettlementReplay> {
}
