package com.musealgo.oj.community.post;

import com.musealgo.oj.community.entity.Post;

/** Central post target rules shared by reads, interactions, comments, and edits. */
public interface PostTargetService {
    Post requireExisting(Long postId);

    Post requireReadable(Long postId, Long actorId);

    boolean canReadClass(Long classId, Long actorId);

    void requireClassAccess(Long classId, Long actorId);

    Post requireEditable(Long postId, Long actorId);

    Post requireCommentTarget(Long postId, Integer commentType);
}
