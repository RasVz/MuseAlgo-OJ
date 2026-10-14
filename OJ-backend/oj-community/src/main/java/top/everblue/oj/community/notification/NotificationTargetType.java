package top.everblue.oj.community.notification;

/** Targets understood by inbox navigation. */
public enum NotificationTargetType {
    POST("post"), PROBLEM("problem");
    private final String value;
    NotificationTargetType(String value) { this.value = value; }
    public String value() { return value; }
}
