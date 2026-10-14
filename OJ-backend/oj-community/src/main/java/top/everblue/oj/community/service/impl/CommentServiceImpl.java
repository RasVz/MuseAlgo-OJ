package top.everblue.oj.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.community.comment.CommentTarget;
import top.everblue.oj.community.comment.CommentTargetDetails;
import top.everblue.oj.community.comment.CommentTargetHandler;
import top.everblue.oj.community.notification.NotificationCommand;
import top.everblue.oj.community.notification.NotificationType;
import top.everblue.oj.community.entity.Comment;
import top.everblue.oj.api.user.UserDisplayInfo;
import top.everblue.oj.community.entity.dto.CommentDTO;
import top.everblue.oj.community.entity.vo.CommentVO;
import top.everblue.oj.community.mapper.CommentMapper;
import top.everblue.oj.community.service.CommentService;
import top.everblue.oj.community.service.LikeService;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.community.service.NotificationService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评论的发布与列表组装。
 * <p>
 * <b>为什么把「评论对象」的规则外置成 {@link CommentTargetHandler}：</b>
 * 评论可以挂在动态、题目、题解三类对象上，而「对象是否存在、是否可见、
 * 评论后要不要给作者发通知、通知的跳转类型是什么」这四个问题，
 * 每类对象的答案都不同，且随时会新增对象类型。
 * 本类只负责跨类型通用的部分（登录校验、楼中楼父子关系、VO 组装），
 * 类型相关的部分交给按 {@code entityType} 注册的处理器。
 * 注册表在构造时建立，同一 {@code entityType} 被两个处理器声明会直接
 * {@link IllegalStateException} 启动失败——宁可启动崩，也不要让
 * 「谁处理这个类型」取决于 Spring 的注入顺序。
 * </p>
 * <p>
 * <b>列表组装的性能取舍：</b>
 * 用户信息一次性批量取回（{@link UserQuery#findDisplayInfoBatch}），
 * 点赞数逐条查 Redis。这是刻意的：用户信息表在另一个模块，
 * 批量接口是跨模块只读契约，按条调用会造成调用方 N+1；而点赞态只在 Redis，
 * 每条评论两次 O(1) 调用，在单页量级下远比多打一次数据库便宜。
 * 代价是评论条数很大时点赞查询成为瓶颈——真到那一步应改为 pipeline 批量取。
 * </p>
 */
@Service
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements CommentService {

    private final LikeService likeService;

    private final UserQuery userQuery;
    private final NotificationService notificationService;
    private final Map<Integer, CommentTargetHandler> targetHandlers;

    public CommentServiceImpl(LikeService likeService, UserQuery userQuery, NotificationService notificationService,
                              List<CommentTargetHandler> handlers) {
        this.likeService = likeService;
        this.userQuery = userQuery;
        this.notificationService = notificationService;
        this.targetHandlers = mapHandlers(handlers);
    }

    /**
     * 发布一条评论。
     * <p>
     * 整个方法在事务内：评论落库、对象侧计数（如动态的 {@code comment_count}）、
     * 通知投递是一组操作。通知侧本身会静默吞掉异常，不会成为回滚诱因。
     * </p>
     * <p>
     * 评论者一律取当前登录态，不接受 DTO 中的用户 ID，避免冒名发表。
     * 通知的接收方同理取自服务端：回复时给被回复人，否则给对象作者，
     * 两者都不信任前端传入的用户 ID。
     * </p>
     *
     * @param dto 评论内容与目标；{@code parentId} 为空时按根评论（0）处理
     * @throws RuntimeException        未登录
     * @throws IllegalArgumentException 目标类型不支持、目标不存在，或父评论/被回复评论不属于当前讨论区
     * @throws IllegalStateException  落库失败
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishComment(CommentDTO dto) {
        Long currentUserId = SecurityUtils.getUserId();
        if(currentUserId == null) throw new RuntimeException("请先登录");

        CommentTarget target = new CommentTarget(dto.getEntityId(), dto.getEntityType());
        CommentTargetDetails targetDetails = handlerFor(target).requireTarget(target.id(), target.type());
        validateReply(dto, target);

        Comment comment = new Comment();
        BeanUtils.copyProperties(dto, comment);
        comment.setUserId(currentUserId);

        // 如果 parentId 为空，默认为 0
        if(comment.getParentId() == null) comment.setParentId(0L);

        if (!this.save(comment)) {
            throw new IllegalStateException("评论发布失败");
        }
        handlerFor(target).afterCommentPublished(comment, targetDetails);
        if (dto.getReplyUserId() != null && dto.getReplyUserId() != 0) {
            notificationService.notify(new NotificationCommand(dto.getReplyUserId(), currentUserId, NotificationType.REPLY,
                    targetDetails.notificationTargetType(), dto.getEntityId(), "有人回复了你的评论", dto.getContent()));
        } else if (targetDetails.notificationRecipientId() != null) {
            notificationService.notify(new NotificationCommand(targetDetails.notificationRecipientId(), currentUserId, NotificationType.COMMENT,
                    targetDetails.notificationTargetType(), dto.getEntityId(), "有人评论了你的内容", dto.getContent()));
        }
    }

    /**
     * 查询某对象下的评论列表。
     * <p>
     * 先过一遍处理器做目标可见性校验：已删除的动态不该还能读到它的评论。
     * </p>
     * <p>
     * 返回的是<b>平铺</b>列表（按时间倒序），不分「根评论 + 楼中楼」两层。
     * {@link CommentVO} 的 {@code children} 字段不会被填充——目前全仓无写入方，
     * 楼中楼由前端按 {@code parentId} 自行分组。这样做的好处是响应体扁平、易分页，
     * 代价是前端要自己拼一次分组。
     * </p>
     *
     * @return 评论列表，无评论时返回空集合而非 {@code null}
     * @throws IllegalArgumentException 目标类型不支持或目标不存在
     */
    @Override
    public List<CommentVO> getCommentList(CommentTarget target) {
        handlerFor(target).requireTarget(target.id(), target.type());
        // 1. 查数据库
        List<Comment> comments = this.list(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getEntityId, target.id())
                .eq(Comment::getEntityType, target.type())
                .orderByDesc(Comment::getCreateTime));

        if (comments.isEmpty()) return new ArrayList<>();

        // 2. 收集所有涉及到的 UserID (包括发评论的、被回复的)
        Set<Long> userIds = comments.stream().map(Comment::getUserId).collect(Collectors.toSet());
        comments.forEach(c -> {
            if (c.getReplyUserId() != null && c.getReplyUserId() != 0) {
                userIds.add(c.getReplyUserId());
            }
        });

        // 3. 一次性查出所有用户（走 oj-api 只读契约，Map 映射）
        Map<Long, UserDisplayInfo> userMap = userQuery.findDisplayInfoBatch(userIds);

        // 4. 组装 VO (合并：基础信息 + 用户信息 + Redis点赞信息)
        return comments.stream().map(c -> {
            CommentVO vo = new CommentVO();
            BeanUtils.copyProperties(c, vo);

            // A. 填入发评论者的头像昵称
            UserDisplayInfo u = userMap.get(c.getUserId());
            if (u != null) {
                vo.setNickname(u.nickname());
                vo.setAvatar(u.avatar());
            }

            // B. 填入被回复者的昵称
            if (c.getReplyUserId() != null && c.getReplyUserId() != 0) {
                UserDisplayInfo replyU = userMap.get(c.getReplyUserId());
                if (replyU != null) {
                    vo.setReplyNickname(replyU.nickname());
                } else {
                    vo.setReplyNickname("未知用户");
                }
            }

            // C. 填入 Redis 实时点赞数据
            vo.setLikeCount(likeService.getLikeCount(c.getId()));
            vo.setIsLiked(likeService.isLiked(c.getId()));

            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 校验楼中楼引用的父评论 / 被回复评论确实属于当前讨论区。
     * <p>
     * <b>这是防跨对象挂评论的关键一步：</b>
     * {@code parentId} 来自前端，若不校验其 {@code entityId} / {@code entityType}，
     * 就能把 A 动态下的评论挂到 B 动态的楼中楼里，两边计数与通知都会错乱。
     * </p>
     * <p>
     * {@code replyUserId} 在此被<b>覆盖</b>为服务端查到的真实作者：
     * 前端传值仅用于一致性检查（传错则报错），不作为事实来源。
     * </p>
     *
     * @throws IllegalArgumentException 引用不存在、已删除、跨讨论区，或被回复人与评论作者不符
     */
    private void validateReply(CommentDTO dto, CommentTarget target) {
        boolean hasParent = dto.getParentId() != null && dto.getParentId() != 0;
        boolean hasReplyTarget = dto.getReplyCommentId() != null && dto.getReplyCommentId() != 0;
        if (!hasParent && !hasReplyTarget) return;

        if (hasParent) requireCommentInTarget(dto.getParentId(), target, "父评论不存在或不属于当前讨论区");
        if (hasReplyTarget) {
            Comment replyTarget = requireCommentInTarget(dto.getReplyCommentId(), target, "被回复评论不存在或不属于当前讨论区");
            if (dto.getReplyUserId() != null && !dto.getReplyUserId().equals(replyTarget.getUserId())) {
                throw new IllegalArgumentException("被回复用户与评论不一致");
            }
            dto.setReplyUserId(replyTarget.getUserId());
        }
    }

    private Comment requireCommentInTarget(Long commentId, CommentTarget target, String message) {
        Comment comment = this.getById(commentId);
        if (comment == null || Integer.valueOf(1).equals(comment.getIsDeleted())
                || !target.id().equals(comment.getEntityId()) || !target.type().equals(comment.getEntityType())) {
            throw new IllegalArgumentException(message);
        }
        return comment;
    }

    /**
     * 按类型取出处理器，取不到直接拒绝。
     * <p>
     * 先调 {@link CommentTarget#validate()} 再查表：类型非法时给出更准确的报错，
     * 而不是笼统的「不支持的类型」。
     * </p>
     *
     * @throws IllegalArgumentException 类型非法，或该类型没有注册处理器
     */
    private CommentTargetHandler handlerFor(CommentTarget target) {
        target.validate();
        CommentTargetHandler handler = targetHandlers.get(target.type());
        if (handler == null) throw new IllegalArgumentException("不支持的评论对象类型");
        return handler;
    }

    /**
     * 建立 {@code entityType → handler} 注册表。
     * <p>
     * <b>重复声明时抛异常而非静默覆盖：</b>静默覆盖的结果取决于
     * Spring 注入 {@code List} 的顺序，而那个顺序不应决定业务路由。
     * 启动期失败比线上「某个类型的评论悄悄发到别的分区」安全得多。
     * </p>
     * <p>
     * 返回 {@link Map#copyOf} 的不可变映射，防止运行期被改动。
     * </p>
     *
     * @throws IllegalStateException 同一 {@code entityType} 被多个处理器声明
     */
    private Map<Integer, CommentTargetHandler> mapHandlers(List<CommentTargetHandler> handlers) {
        Map<Integer, CommentTargetHandler> mapped = new HashMap<>();
        for (CommentTargetHandler handler : handlers) {
            for (Integer type : handler.supportedTypes()) {
                if (mapped.put(type, handler) != null) {
                    throw new IllegalStateException("评论对象类型只能有一个处理器: " + type);
                }
            }
        }
        return Map.copyOf(mapped);
    }
}
