package top.everblue.oj.community.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import top.everblue.oj.community.entity.UserLike;
import top.everblue.oj.community.mapper.UserLikeMapper;
import top.everblue.oj.community.service.UserLikeService;

/**
 * 用户点赞/踩记录 Service 实现类
 */
@Service
public class UserLikeServiceImpl extends ServiceImpl<UserLikeMapper, UserLike> implements UserLikeService {
}
