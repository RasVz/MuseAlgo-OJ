package com.musealgo.oj.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.musealgo.oj.community.entity.UserLike;

/**
 * 用户点赞/踩记录 Mapper 接口
 */
@Mapper
public interface UserLikeMapper extends BaseMapper<UserLike> {
}
