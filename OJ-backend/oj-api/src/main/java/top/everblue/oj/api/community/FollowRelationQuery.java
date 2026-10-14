package top.everblue.oj.api.community;

import java.util.List;

/** 关注关系只读查询契约（社区模块提供，提交可见性过滤用）。 */
public interface FollowRelationQuery {

    /**
     * 查询某用户关注的全部用户 ID。
     *
     * @param followerId 关注者用户 ID
     * @return 被关注用户 ID 列表
     */
    List<Long> findFollowedUserIds(Long followerId);
}
