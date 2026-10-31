package com.musealgo.oj.community.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.community.notification.NotificationCommand;
import com.musealgo.oj.community.notification.NotificationType;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 站内通知的写入、查询与已读状态维护。
 * <p>
 * <b>为什么用 JdbcTemplate 而不是 MyBatis-Plus：</b>
 * {@code user_notification} 没有对应的实体类，且查询要跨 {@code user} 表取操作者昵称、
 * 还要按「今日范围」动态拼接条件、按偏好动态拼接 {@code NOT IN} 列表——
 * 这些用注解 SQL 写出来只是把 SQL 藏进字符串，而本类的逻辑重心在
 * 「通知偏好过滤」而非 SQL 本身。写回实体 + BaseMapper 反而会让那部分逻辑更难读。
 * </p>
 * <p>
 * <b>偏好过滤放在应用层而非 SQL：</b>
 * {@link #list} 查出后用 {@code removeIf} 过滤而非在 SQL 里拼条件，
 * 换来的是「关闭互动通知时仍可正常分页 / 计数」的一致语义：
 * {@link #unreadCount} 的偏好分支必须把 {@code NOT IN} 塞进 {@code COUNT}，
 * 若两处过滤规则不同步，用户会看到未读角标为 0 但列表里还有未读消息。
 * 代价是过滤在数据库之后发生，列表最多取 100 条（见 SQL 中的固定 LIMIT）。
 * </p>
 * <p>
 * 通知写入是<b>静默失败</b>的：{@link #persist} 与 {@link #notify} 遇空值直接 return，
 * 不抛异常。通知属于旁路能力，用户点赞即使通知落库失败也不应回滚主流程。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** 互动类通知类型（受"互动通知"开关控制） */
    private static final Set<String> INTERACTION_TYPES = Set.of(
            "LIKE", "FAVORITE", "REPLY", "COMMENT", "FOLLOW_POST", "MODERATION");
    private static final Set<String> REPORT_TYPES = Set.of("REPORT_READY", "REPORT_FAILED");

    /** 用户在偏好设置中配置的通知开关 */
    public record NotificationPrefs(boolean interactions, boolean homework, boolean contest, boolean reports) {
        public static final NotificationPrefs DEFAULTS = new NotificationPrefs(true, true, true, true);
    }

    /**
     * 落库一条通知。
     * <p>
     * 自发自收不生成通知（点赞自己的动态不应收到提醒）。
     * 内容超长截断至 480 字符——列宽按通知栏气泡一屏可读设定，
     * 完整正文由前端按 {@code targetId} 回查动态获取。
     * </p>
     *
     * @param recipientId 接收者；与 {@code actorId} 相同时直接跳过
     */
    private void persist(Long recipientId, Long actorId, String type, String targetType, Long targetId, String title, String content) {
        if (recipientId == null || recipientId.equals(actorId)) return;
        jdbcTemplate.update("INSERT INTO user_notification (user_id, actor_id, type, target_type, target_id, title, content) VALUES (?, ?, ?, ?, ?, ?, ?)",
                recipientId, actorId, type, targetType, targetId, title, abbreviate(content));
    }

    /**
     * 按命令对象投递通知。
     * <p>
     * 命令缺类型或目标类型时静默丢弃：调用方遍布点赞、评论、关注等链路，
     * 让它们各自判断完整性会让调用点变噪音。
     * </p>
     */
    public void notify(NotificationCommand command) {
        if (command == null || command.type() == null || command.targetType() == null) return;
        persist(command.recipientId(), command.actorId(), command.type().name(), command.targetType().value(),
                command.targetId(), command.title(), command.content());
    }

    /**
     * 查询通知列表。
     *
     * @param scope 传 {@code "today"} 时只取今日；其他值（含 {@code null}）取全部
     * @return 最新 100 条，按时间倒序；已按用户的互动通知偏好过滤
     */
    public List<Map<String, Object>> list(Long userId, String scope) {
        String where = "today".equals(scope) ? " AND n.create_time >= CURDATE()" : "";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT n.id, n.type, n.target_type AS targetType, n.target_id AS targetId, n.title, n.content, n.is_read AS isRead, n.create_time AS createTime, "
                + "u.nickname AS actorName, u.avatar AS actorAvatar FROM user_notification n LEFT JOIN user u ON u.id = n.actor_id "
                + "WHERE n.user_id = ?" + where + " ORDER BY n.create_time DESC LIMIT 100", userId);
        NotificationPrefs prefs = loadPreferences(userId);
        if (!prefs.interactions()) {
            rows.removeIf(row -> INTERACTION_TYPES.contains(String.valueOf(row.get("type"))));
        }
        if (!prefs.reports()) rows.removeIf(row -> REPORT_TYPES.contains(String.valueOf(row.get("type"))));
        return rows;
    }

    /**
     * 未读通知数，供角标展示。
     * <p>
     * 与 {@link #list} 复用同一套偏好规则，但计数必须在 SQL 内过滤——
     * 角标是 {@code COUNT(*)}，应用层拿不到未过滤的全量行数。
     * </p>
     *
     * @return 未读条数，无数据时为 0（不返回 {@code null}）
     */
    public int unreadCount(Long userId) {
        NotificationPrefs prefs = loadPreferences(userId);
        String sql = "SELECT COUNT(*) FROM user_notification WHERE user_id = ? AND is_read = 0";
        List<String> excluded = new ArrayList<>();
        if (!prefs.interactions()) excluded.addAll(INTERACTION_TYPES);
        if (!prefs.reports()) excluded.addAll(REPORT_TYPES);
        List<Object> args = new ArrayList<>(); args.add(userId);
        if (!excluded.isEmpty()) {
            sql += " AND type NOT IN (" + String.join(",", Collections.nCopies(excluded.size(), "?")) + ")";
            args.addAll(excluded);
        }
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** 读取用户通知偏好（默认全部开启）
     * <p>
     * 偏好存在 {@code user.preferences} 的 JSON 里而非独立表，且各开关缺失时一律
     * 视为开启（{@code asBoolean(true)}）：老用户没有该字段，若按缺省关闭处理，
     * 上线后会突然发现所有人的互动通知都关了。
     * </p>
     * <p>
     * 整段 {@code catch} 后返回默认值是<b>故意的 fail-open</b>：
     * 偏好读不出来不该导致通知功能整体不可用，用「全部开启」降级最安全。
     * </p>
     */
    public NotificationPrefs loadPreferences(Long userId) {
        if (userId == null) return NotificationPrefs.DEFAULTS;
        try {
            String json = jdbcTemplate.queryForObject(
                    "SELECT preferences FROM user WHERE id = ?", String.class, userId);
            if (json == null || json.isBlank()) return NotificationPrefs.DEFAULTS;
            JsonNode node = objectMapper.readTree(json).path("notifications");
            return new NotificationPrefs(
                    node.path("interactions").asBoolean(true),
                    node.path("homework").asBoolean(true),
                    node.path("contest").asBoolean(true),
                    node.path("reports").asBoolean(true));
        } catch (Exception e) {
            return NotificationPrefs.DEFAULTS;
        }
    }

    /**
     * Strict preference read for reliable report dispatch. Missing user preference
     * fields keep the legacy enabled-by-default behavior; an unreadable row or
     * malformed preference document fails closed by propagating an exception.
     * The generic interaction-notification path deliberately keeps its existing
     * fail-open {@link #loadPreferences(Long)} behavior.
     */
    public NotificationPrefs loadReportPreferencesStrict(Long userId) {
        if (userId == null) throw new IllegalArgumentException("userId is required");
        String json = jdbcTemplate.queryForObject(
                "SELECT preferences FROM user WHERE id = ?", String.class, userId);
        if (json == null || json.isBlank()) return NotificationPrefs.DEFAULTS;
        final JsonNode notifications;
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("Invalid notification preference document");
            }
            JsonNode node = root.get("notifications");
            if (node == null) return NotificationPrefs.DEFAULTS;
            if (!node.isObject()) throw new IllegalStateException("Invalid notification preference shape");
            notifications = node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unable to parse notification preferences", e);
        }
        return new NotificationPrefs(
                strictBoolean(notifications, "interactions"),
                strictBoolean(notifications, "homework"),
                strictBoolean(notifications, "contest"),
                strictBoolean(notifications, "reports"));
    }

    private boolean strictBoolean(JsonNode notifications, String field) {
        JsonNode value = notifications.get(field);
        if (value == null || value.isMissingNode()) return true;
        if (!value.isBoolean()) throw new IllegalStateException("Invalid notification preference: " + field);
        return value.booleanValue();
    }

    /**
     * Inserts exactly one in-app report notification inside the caller's dispatch transaction and
     * returns its generated id. The caller has already fenced the durable intent and must keep the
     * insert and the intent's DELIVERED transition in the same transaction, so a rollback cannot
     * leave a notification without its intent link.
     * <p>
     * This method intentionally does not consult preferences or authorization: those facts are
     * read by the dispatcher before opening this short transaction.
     * </p>
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Long createReportNotification(Long recipientId, NotificationType type, Long jobId,
                                         String title, String content) {
        if (recipientId == null || jobId == null || type == null) {
            throw new IllegalArgumentException("报告通知收件人、类型与任务不能为空");
        }
        if (type != NotificationType.REPORT_READY && type != NotificationType.REPORT_FAILED) {
            throw new IllegalArgumentException("仅支持报告类站内通知");
        }
        if (title == null || title.isBlank() || title.length() > 160) {
            throw new IllegalArgumentException("报告通知标题无效");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO user_notification (user_id, actor_id, type, target_type, target_id, title, content) "
                            + "VALUES (?, NULL, ?, 'REPORT', ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, recipientId);
            statement.setString(2, type.name());
            statement.setLong(3, jobId);
            statement.setString(4, title);
            statement.setString(5, abbreviate(content));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("NOTIFICATION_ID_UNAVAILABLE");
        }
        return key.longValue();
    }

    /**
     * 标记单条已读。
     * <p>
     * {@code WHERE} 里带 {@code user_id}：没有这层校验，前端传别人的通知 ID
     * 就能改动他人已读状态。
     * </p>
     */
    public void markRead(Long userId, Long id) {
        jdbcTemplate.update("UPDATE user_notification SET is_read = 1, read_time = NOW() WHERE id = ? AND user_id = ?", id, userId);
    }

    /**
     * 标记全部未读为已读。
     * <p>
     * {@code AND is_read = 0} 不是冗余条件：它让重复调用不刷新 {@code read_time}，
     * 保证「已读时间」记录的是用户第一次读到的时刻，而不是最后一次批量操作时刻。
     * </p>
     */
    public void markAllRead(Long userId) {
        jdbcTemplate.update("UPDATE user_notification SET is_read = 1, read_time = NOW() WHERE user_id = ? AND is_read = 0", userId);
    }

    private String abbreviate(String value) {
        if (value == null) return null;
        return value.length() > 480 ? value.substring(0, 480) + "…" : value;
    }
}
