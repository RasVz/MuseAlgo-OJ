package com.musealgo.oj.community.content;

import com.musealgo.oj.community.entity.Post;

/** Owns post publication and editing invariants. */
public interface PostContentService {
    boolean publish(Post post, Long authorId);

    boolean update(Post post, Long actorId);
}
