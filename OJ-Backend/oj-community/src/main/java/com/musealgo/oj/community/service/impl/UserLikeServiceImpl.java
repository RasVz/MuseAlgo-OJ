package com.musealgo.oj.community.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import com.musealgo.oj.community.entity.UserLike;
import com.musealgo.oj.community.mapper.UserLikeMapper;
import com.musealgo.oj.community.service.UserLikeService;

/**
 * 用户点赞/踩记录 Service 实现类
 */
@Service
public class UserLikeServiceImpl extends ServiceImpl<UserLikeMapper, UserLike> implements UserLikeService {
}
