package top.everblue.oj.community.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import top.everblue.oj.community.entity.UserFavorite;
import top.everblue.oj.community.mapper.UserFavoriteMapper;
import top.everblue.oj.community.service.UserFavoriteService;

/**
 * 收藏记录的 MyBatis-Plus 基座实现，仅提供 {@code IService} 的通用 CRUD。
 * <p>
 * <b>业务逻辑不在这里，而在
 * {@link top.everblue.oj.community.favorite.FavoriteApplicationServiceImpl}：</b>
 * 本类被多个组件直接注入使用（互动处理器、收藏应用服务），
 * 它们只需要最基础的按条件查询与更新，不需要收藏的业务规则。
 * 把状态机、计数回写等逻辑塞进来会让这个基座不再是「通用 CRUD」，
 * 也会让依赖它的互动处理器被绑上收藏业务的规则。
 * </p>
 */
@Service
public class UserFavoriteServiceImpl extends ServiceImpl<UserFavoriteMapper, UserFavorite> implements UserFavoriteService {
}
