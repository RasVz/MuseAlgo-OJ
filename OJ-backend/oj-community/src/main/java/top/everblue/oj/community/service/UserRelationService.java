package top.everblue.oj.community.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import top.everblue.oj.community.entity.vo.UserRelationVO;

/**
 * 用户关注关系服务。
 */
public interface UserRelationService {

    /**
     * 关注/取关二合一。返回 "followed" 或 "unfollowed"。
     */
    String toggleFollow(Long currentUserId, Long targetUserId);

    /**
     * 关注列表（分页，带 viewer 视角的互关状态）。
     */
    Page<UserRelationVO> selectFollowingList(Page<?> page, Long userId, Long viewerId);

    /**
     * 粉丝列表（分页，带 viewer 视角的互关状态）。
     */
    Page<UserRelationVO> selectFollowerList(Page<?> page, Long userId, Long viewerId);

    /**
     * viewer 是否已关注 userId（exists 查询）。
     */
    boolean isFollowing(Long viewerId, Long userId);
}
