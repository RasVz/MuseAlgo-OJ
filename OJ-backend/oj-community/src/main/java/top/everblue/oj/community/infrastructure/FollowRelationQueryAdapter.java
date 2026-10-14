package top.everblue.oj.community.infrastructure;

import org.springframework.stereotype.Service;
import top.everblue.oj.api.community.FollowRelationQuery;
import top.everblue.oj.community.mapper.UserRelationMapper;

import java.util.Collections;
import java.util.List;

/**
 * 把「查询我关注了谁」以只读契约的形式开放给其他模块。
 * <p>
 * 存在的意义是<b>反向依赖</b>：通知、教学等模块需要判断「当前用户是否关注了某人」，
 * 但 {@code user_relation} 表及其实体属于社区模块。
 * 让它们依赖 oj-api 里的 {@link FollowRelationQuery} 接口而非社区模块的实现，
 * 可避免模块之间出现直接依赖与循环依赖。
 * </p>
 * <p>
 * 本类是适配器，不是业务逻辑所在处——关注关系的写入与校验都在
 * {@link top.everblue.oj.community.service.UserRelationServiceImpl}。
 * </p>
 */
@Service
public class FollowRelationQueryAdapter implements FollowRelationQuery {

    private final UserRelationMapper userRelationMapper;

    public FollowRelationQueryAdapter(UserRelationMapper userRelationMapper) {
        this.userRelationMapper = userRelationMapper;
    }

    /**
     * 查询某人关注的用户 ID 列表。
     * <p>
     * {@code followerId} 为 {@code null} 时返回空集合而非 {@code null}：
     * 调用方普遍直接遍历返回值，返回 {@code null} 会把空值判断的负担
     * 推给每一个调用点。
     * </p>
     */
    @Override
    public List<Long> findFollowedUserIds(Long followerId) {
        return followerId == null ? Collections.emptyList() : userRelationMapper.selectFollowedIds(followerId);
    }
}
