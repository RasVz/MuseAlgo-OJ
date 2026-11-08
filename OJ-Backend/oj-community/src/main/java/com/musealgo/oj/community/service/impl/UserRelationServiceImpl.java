package com.musealgo.oj.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.community.entity.UserRelation;
import com.musealgo.oj.community.entity.vo.UserRelationVO;
import com.musealgo.oj.community.mapper.UserRelationMapper;
import com.musealgo.oj.community.service.UserRelationService;
import com.musealgo.oj.api.user.UserStatsCommand;

/**
 * 关注关系的建立与解除，以及粉丝/关注列表查询。
 * <p>
 * <b>取关是物理删除，而收藏是软取消——两者刻意不同：</b>
 * 关注关系没有「曾关注过」的业务价值，删掉即释放；
 * 收藏则要保留 {@code createTime}（见 {@link com.musealgo.oj.community.favorite}）。
 * 若后续给关注也加软删除，请一并确认计数回滚逻辑。
 * </p>
 * <p>
 * 关注数 / 粉丝数经 oj-api 的 {@code UserStatsCommand} 回写到用户模块，
 * 社区侧不直接改 {@code user} 表——用户表不归本模块所有。
 * 两处计数在同一事务内更新，保证关系与计数不会脱节。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class UserRelationServiceImpl implements UserRelationService {

    private final UserRelationMapper userRelationMapper;
    private final UserStatsCommand userStatsCommand;

    /**
     * 关注 / 取关。
     * <p>
     * <b>方法名是「toggle」但调用方不应依赖它：</b>内部是「先查后插/删」，
     * 并发下两个请求可能同时查到「无记录」而双双插入。
     * {@code user_relation} 上有 {@code (follower_id, followed_id)} 唯一约束兜底，
     * 后到者返回状态冲突并回滚；计数只在本次关系插入成功后更新。
     * </p>
     * <p>
     * 返回英文状态串（{@code followed} / {@code unfollowed}）供前端提示，
     * 但它表达的是「本次操作的结果」，不是「当前关注状态」——
     * 两次调用返回相同不代表状态未变。
     * </p>
     *
     * @return {@code "followed"} 或 {@code "unfollowed"}
     * @throws com.musealgo.oj.common.exception.BusinessException 并发关注同一用户时
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String toggleFollow(Long currentUserId, Long targetUserId) {
        LambdaQueryWrapper<UserRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRelation::getFollowerId, currentUserId)
                .eq(UserRelation::getFollowedId, targetUserId);

        UserRelation relation = userRelationMapper.selectOne(wrapper);

        if (relation != null) {
            // A concurrent unfollow may already have removed this relation.
            if (userRelationMapper.deleteById(relation.getId()) == 0) return "unfollowed";
            userStatsCommand.incrementFollowerCount(targetUserId, -1);
            userStatsCommand.incrementFollowingCount(currentUserId, -1);
            return "unfollowed";
        } else {
            UserRelation newRelation = new UserRelation();
            newRelation.setFollowerId(currentUserId);
            newRelation.setFollowedId(targetUserId);
            try {
                if (userRelationMapper.insert(newRelation) != 1) throw new IllegalStateException("关注关系保存失败");
            } catch (org.springframework.dao.DuplicateKeyException conflict) {
                throw new com.musealgo.oj.common.exception.BusinessException(
                        com.musealgo.oj.common.exception.CommonErrorCode.STATE_CONFLICT, "关注状态已变化，请刷新后重试");
            }
            userStatsCommand.incrementFollowerCount(targetUserId, 1);
            userStatsCommand.incrementFollowingCount(currentUserId, 1);
            return "followed";
        }
    }

    @Override
    public Page<UserRelationVO> selectFollowingList(
            Page<?> page, Long userId, Long viewerId) {
        return userRelationMapper.selectFollowingList(page, userId, viewerId);
    }

    @Override
    public Page<UserRelationVO> selectFollowerList(
            Page<?> page, Long userId, Long viewerId) {
        return userRelationMapper.selectFollowerList(page, userId, viewerId);
    }

    @Override
    public boolean isFollowing(Long viewerId, Long userId) {
        return userRelationMapper.exists(
                new LambdaQueryWrapper<UserRelation>()
                        .eq(UserRelation::getFollowerId, viewerId)
                        .eq(UserRelation::getFollowedId, userId));
    }
}
