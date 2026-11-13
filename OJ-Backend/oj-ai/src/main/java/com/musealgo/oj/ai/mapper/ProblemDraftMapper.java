package com.musealgo.oj.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.musealgo.oj.ai.entity.ProblemDraft;
import org.apache.ibatis.annotations.Mapper;

/** AI 题面生成草稿（V75）。 */
@Mapper
public interface ProblemDraftMapper extends BaseMapper<ProblemDraft> {
}
