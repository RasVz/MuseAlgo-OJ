package top.everblue.oj.community.post;

import top.everblue.oj.community.entity.Post;

/** Central post target rules shared by reads, interactions, comments, and edits. */
public interface PostTargetService {
    Post requireExisting(Long postId);

    Post requireEditable(Long postId, Long actorId);

    Post requireCommentTarget(Long postId, Integer commentType);
}
