package com.musealgo.oj.judge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.musealgo.oj.judge.entity.SubmissionCode;

/**
 * 提交源码 Mapper（submission_code，V52 拆表）。
 */
@Mapper
public interface SubmissionCodeMapper extends BaseMapper<SubmissionCode> {
}
