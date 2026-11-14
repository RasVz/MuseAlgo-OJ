package com.musealgo.oj.ai.mapper;

import com.musealgo.oj.ai.review.SubmissionReview;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ReviewMapper extends BaseMapper<SubmissionReview> {
    @Select("SELECT * FROM ai_submission_review WHERE id=#{id}")
    SubmissionReview full(@Param("id") Long id);
}
