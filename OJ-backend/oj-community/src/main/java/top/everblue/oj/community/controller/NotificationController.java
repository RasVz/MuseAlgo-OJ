package top.everblue.oj.community.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.community.service.NotificationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 站内通知的查询与已读标记接口。
 * <p>
 * <b>通知列表不是纯 {@code user_notification} 表的投影：</b>
 * 列表接口会把「即将开始的作业 / 比赛」这类<b>时效性提醒</b>
 * （由 {@link #upcomingTasks} 从各业务表按时间实时算出）追加到通知尾部，
 * 与落库通知混在一起返回。这样做省掉一张提醒任务表，
 * 代价是提醒每次请求都要现算、且没有「已读」概念。
 * 前端因此不能假设列表里的每一项都能调用
 * {@link top.everblue.oj.community.service.NotificationService#markRead}。
 * </p>
 */
@RestController
@RequestMapping("/notification")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notificationService;
    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list(@RequestParam(defaultValue = "all") String scope) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.error(401, "请先登录");
        NotificationService.NotificationPrefs prefs = notificationService.loadPreferences(userId);
        List<Map<String, Object>> list = new ArrayList<>(notificationService.list(userId, scope));
        if (!"interactions".equals(scope)) list.addAll(upcomingTasks(userId, prefs));
        return Result.success(list);
    }

    /**
     * 未读角标。
     * <p>
     * 未登录返回 0 而非 401：这是页面框架级的高频探针接口，
     * 对未登录访客报错误码会让前端在正常浏览时反复弹登录提示。
     * 需要区分登录态的判断请放在列表接口上。
     * </p>
     */
    @GetMapping("/unread-count")
    public Result<Integer> unreadCount() {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.success(0);
        return Result.success(notificationService.unreadCount(userId));
    }

    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.error(401, "请先登录");
        notificationService.markRead(userId, id);
        return Result.success(null);
    }

    @PostMapping("/read-all")
    public Result<Void> markAllRead() {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.error(401, "请先登录");
        notificationService.markAllRead(userId);
        return Result.success(null);
    }

    /**
     * 任务提醒按读取时实时计算，因而不会因服务重启或定时任务延迟而错过。
     * <p>
     * <b>用负数 ID 标记提醒项：</b>落库通知的 ID 为正数、且能在
     * {@code user_notification} 表中找到；提醒项用 {@code -id} 产生一个不可能与真实 ID 冲突的负数，
     * 前端可据此判断「这一项不可标记已读」，也就无需额外字段。
     * 改动此约定时需同步前端。
     * </p>
     * <p>
     * 提醒受用户偏好控制（作业、比赛各自一个开关），互动类提醒则由
     * {@link top.everblue.oj.community.service.NotificationService} 处理——注意「互动」
     * 这一类是唯一被加到落库通知<b>之前</b>过滤的分类。
     * </p>
     * <p>
     * 每类各取 10 条、时间窗为未来 24 小时，均写死在 SQL 里。
     * </p>
     *
     * @return 提醒项列表，无提醒时为空集合
     */
    private List<Map<String, Object>> upcomingTasks(Long userId, NotificationService.NotificationPrefs prefs) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        if (prefs.homework()) {
            tasks.addAll(jdbcTemplate.queryForList("SELECT -p.id AS id, 'HOMEWORK_REMINDER' AS type, 'homework' AS targetType, p.id AS targetId, "
                            + "CONCAT('作业即将截止：', p.title) AS title, CONCAT('截止时间：', DATE_FORMAT(p.end_time, '%m-%d %H:%i')) AS content, "
                            + "0 AS isRead, p.end_time AS createTime, NULL AS actorName, NULL AS actorAvatar "
                            + "FROM problem_list p JOIN class_student cs ON cs.class_id = p.class_id "
                            + "WHERE cs.student_id = ? AND p.type = 4 AND p.privacy = 1 AND p.end_time BETWEEN NOW() AND DATE_ADD(NOW(), INTERVAL 24 HOUR) "
                            + "ORDER BY p.end_time ASC LIMIT 10", userId));
        }
        if (prefs.contest()) {
            tasks.addAll(jdbcTemplate.queryForList("SELECT -c.id AS id, 'CONTEST_REMINDER' AS type, 'contest' AS targetType, c.id AS targetId, "
                            + "CONCAT('比赛即将开始：', c.title) AS title, CONCAT('开始时间：', DATE_FORMAT(c.start_time, '%m-%d %H:%i')) AS content, "
                            + "0 AS isRead, c.start_time AS createTime, NULL AS actorName, NULL AS actorAvatar "
                            + "FROM contest c JOIN contest_user cu ON cu.contest_id = c.id "
                            + "WHERE cu.user_id = ? AND c.visible = 1 AND c.start_time BETWEEN NOW() AND DATE_ADD(NOW(), INTERVAL 24 HOUR) "
                            + "ORDER BY c.start_time ASC LIMIT 10", userId));
        }
        return tasks;
    }
}
