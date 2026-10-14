package top.everblue.oj.community.service;

import com.baomidou.mybatisplus.extension.service.IService;
import top.everblue.oj.community.entity.UserFavorite;

/**
 * 收藏记录的数据访问基座接口，只提供 MyBatis-Plus 的通用 CRUD。
 * <p>
 * <b>保持空接口的原因同 {@link PostService}：</b>收藏的状态机与计数回写集中在
 * {@link top.everblue.oj.community.favorite.FavoriteApplicationService}，
 * 本接口只是被它和互动处理器当作按条件查询的工具。
 * </p>
 * <p>
 * 使用时注意收藏是<b>软取消</b>（{@code status} 0/1），
 * 查询必须带 {@code status} 条件，否则会把已取消的收藏也算进来。
 * </p>
 */
public interface UserFavoriteService extends IService<UserFavorite> {
}
