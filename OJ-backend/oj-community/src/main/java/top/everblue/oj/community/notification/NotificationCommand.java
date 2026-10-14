package top.everblue.oj.community.notification;

/** Complete notification intent; callers no longer compose protocol strings. */
public record NotificationCommand(Long recipientId, Long actorId, NotificationType type,
                                  NotificationTargetType targetType, Long targetId,
                                  String title, String content) {
}
