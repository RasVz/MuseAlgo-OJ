package top.everblue.oj.community.notification;

/** Supported inbox events. Database values remain stable for existing clients. */
public enum NotificationType {
    LIKE, FAVORITE, REPLY, COMMENT, FOLLOW_POST, MODERATION
}
