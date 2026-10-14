package top.everblue.oj.community.comment;

import top.everblue.oj.community.notification.NotificationTargetType;

/** Notification metadata supplied by a validated comment target. */
/**
 * 已通过校验的评论目标所附带的通知元数据。
 * <p>
 * 从 {@link CommentTargetHandler#requireTarget} 一并返回，而不是让
 * {@link top.everblue.oj.community.service.impl.CommentServiceImpl} 再查一遍：
 * 处理器在取详情的同一次查询里已经拿到了作者 ID，
 * 分成两次查既多一次往返，又可能在并发编辑下拿到不一致的两份数据。
 * </p>
 * <p>
 * {@code notificationRecipientId} 为 {@code null} 表示该目标没有可通知的所有者
 * （如题目讨论区），此时不产生通知——这是有意允许的，不是漏填。
 * </p>
 */
public record CommentTargetDetails(NotificationTargetType notificationTargetType, Long notificationRecipientId) {
}
