package com.musealgo.oj.community.comment;

import com.musealgo.oj.community.entity.Comment;

import java.util.Set;

/** Internal seam for rules owned by a concrete comment target. */
public interface CommentTargetHandler {
    Set<Integer> supportedTypes();

    CommentTargetDetails requireTarget(Long targetId, Integer targetType);

    void afterCommentPublished(Comment comment, CommentTargetDetails target);
}
