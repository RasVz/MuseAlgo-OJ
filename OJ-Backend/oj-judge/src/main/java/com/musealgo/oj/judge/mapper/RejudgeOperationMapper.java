package com.musealgo.oj.judge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.musealgo.oj.judge.entity.RejudgeOperation;

/** 重判操作 Mapper（T8）。 */
@Mapper
public interface RejudgeOperationMapper extends BaseMapper<RejudgeOperation> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM rejudge_operation WHERE id = #{id} FOR UPDATE")
    RejudgeOperation selectForUpdate(@org.apache.ibatis.annotations.Param("id") Long id);
}
