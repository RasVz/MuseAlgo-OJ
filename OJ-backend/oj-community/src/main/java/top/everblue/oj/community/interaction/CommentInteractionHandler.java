package top.everblue.oj.community.interaction;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.community.entity.Comment;
import top.everblue.oj.community.service.CommentService;
import top.everblue.oj.community.service.LikeService;
import top.everblue.oj.community.service.NotificationService;
import top.everblue.oj.community.notification.NotificationCommand;
import top.everblue.oj.community.notification.NotificationTargetType;
import top.everblue.oj.community.notification.NotificationType;

import java.util.Map;

/** Owns comment-like state and its notification side effect. */
/**
 * 评论点赞的互动处理器。
 * <p>
 * 与 {@link PostInteractionHandler} 的关键差异：评论点赞<b>只改 Redis</b>，
 * 不碰数据库计数、不刷排行——评论热度不参与动态榜单。
 * 通知是唯一的持久化副作用，且仅在状态真正翻转时才发。
 * </p>
 * <p>
 * 类为包级可见：只经 {@link CommunityInteractionService} 分派使用。
 * </p>
 */
@Service
@RequiredArgsConstructor
class CommentInteractionHandler implements CommunityInteractionHandler {

    private final CommentService commentService;
    private final LikeService likeService;
    private final NotificationService notificationService;

    @Override
    public InteractionTargetType targetType() {
        return InteractionTargetType.COMMENT;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InteractionResult activate(InteractionCommand command) {
        Comment comment = requireComment(command.targetId());
        requireLike(command);
        boolean changed = likeService.activate(command.targetId(), command.actorId());
        if (changed) {
            notificationService.notify(new NotificationCommand(comment.getUserId(), command.actorId(), NotificationType.LIKE,
                    notificationTargetType(comment), comment.getEntityId(), "有人点赞了你的评论", comment.getContent()));
        }
        return result(command.targetId(), changed, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InteractionResult deactivate(InteractionCommand command) {
        requireComment(command.targetId());
        requireLike(command);
        return result(command.targetId(), likeService.deactivate(command.targetId(), command.actorId()), false);
    }

    @Override
    public boolean isActive(InteractionCommand command) {
        requireComment(command.targetId());
        requireLike(command);
        return likeService.isLiked(command.targetId(), command.actorId());
    }

    private InteractionResult result(Long commentId, boolean changed, boolean active) {
        return new InteractionResult(changed, active, Map.of("likes", likeService.getLikeCount(commentId)));
    }

    private Comment requireComment(Long commentId) {
        Comment comment = commentService.getById(commentId);
        if (comment == null || Integer.valueOf(1).equals(comment.getIsDeleted())) {
            throw new IllegalArgumentException("评论不存在");
        }
        return comment;
    }

    private void requireLike(InteractionCommand command) {
        if (command.action() != InteractionAction.LIKE) {
            throw new IllegalArgumentException("该内容不支持此互动");
        }
    }

    private NotificationTargetType notificationTargetType(Comment comment) {
        return Integer.valueOf(2).equals(comment.getEntityType()) ? NotificationTargetType.PROBLEM : NotificationTargetType.POST;
    }
}
