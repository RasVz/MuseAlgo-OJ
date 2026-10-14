package top.everblue.oj.community.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.annotation.AuditLog;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.community.entity.Post;
import top.everblue.oj.community.service.CommunityTagService;
import top.everblue.oj.community.service.NotificationService;
import top.everblue.oj.community.service.PostService;
import top.everblue.oj.community.notification.NotificationCommand;
import top.everblue.oj.community.notification.NotificationTargetType;
import top.everblue.oj.community.notification.NotificationType;

import java.util.List;
import java.util.Map;

/**
 * 社区举报的提交与管理员审核处置。
 * <p>
 * 一条举报从提交到结案的状态机：
 * {@code PENDING} →（隐藏）{@code RESOLVED} /（驳回）{@code DISMISSED}，
 * 状态与处理人、处理备注、结案时间一并落库。
 * </p>
 * <p>
 * <b>处置动作刻意设为「限制可见」而非「删除」：</b>
 * {@link #resolveReport} 在判定隐藏时把 {@code post.privacy} 置 1，
 * 内容从公开列表消失但仍可被作者本人看到。直接软删除会让作者失去自查能力，
 * 也无法在误判后恢复。
 * </p>
 * <p>
 * <b>权限校验统一用方法内 {@code SecurityUtils.isAdmin()} 而非类级 {@code @PreAuthorize}：</b>
 * 举报提交接口需要登录但不限管理员，放在同一个类里，
 * 类级注解会连带禁止普通用户举报。
 * </p>
 * <p>
 * SQL 走 {@link JdbcTemplate} 而非实体 + BaseMapper：审核涉及跨
 * {@code content_report}、{@code post}、{@code user} 三表关联读取，
 * 且这些表分属不同关注边界（举报记录没有对应实体类），建实体的收益低于成本。
 * </p>
 */
@RestController
@RequestMapping("/community")
@RequiredArgsConstructor
public class CommunityGovernanceController {
    private final JdbcTemplate jdbcTemplate;
    private final PostService postService;
    private final CommunityTagService communityTagService;
    private final NotificationService notificationService;

    /**
     * 提交举报。
     * <p>
     * {@code content_report} 上的唯一约束是
     * {@code (reporter_id, post_id, status)}——它约束的是「同一用户对同一内容
     * <b>在同一个处理状态下</b>只能有一条记录」，而不是终身只能举报一次。
     * 因此这里的语义是：待审核（PENDING）期间重复提交会被拒，
     * 但上一条已结案（RESOLVED / DISMISSED）后仍可再次举报。
     * 这符合实际需要——内容被解封后用户应能重新举报。
     * </p>
     * <p>
     * 实现上<b>不</b>先查后插（那样在并发下有窗口、且无法区分是哪种状态冲突），
     * 而是捕获 {@link org.springframework.dao.DuplicateKeyException} 转成友好提示。
     * </p>
     */
    @PostMapping("/report")
    @AuditLog(module = "community", action = "report_post")
    public Result<Void> report(@RequestBody Map<String, String> payload) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.error(401, "请先登录");
        Long postId;
        try { postId = Long.parseLong(payload.get("postId")); } catch (Exception e) { return Result.error("举报对象无效"); }
        String reason = payload.get("reason");
        if (reason == null || reason.isBlank()) return Result.error("请选择举报原因");
        Post post = postService.getById(postId);
        if (post == null || (post.getIsDeleted() != null && post.getIsDeleted() == 1)) return Result.error("内容不存在");
        try {
            jdbcTemplate.update("INSERT INTO content_report (reporter_id, post_id, reason, detail) VALUES (?, ?, ?, ?)", userId, postId, reason, payload.get("detail"));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return Result.error("你已举报过该内容，正在等待审核");
        }
        return Result.success(null);
    }

    @GetMapping("/moderation/reports")
    public Result<List<Map<String, Object>>> reports(@RequestParam(defaultValue = "PENDING") String status) {
        if (!SecurityUtils.isAdmin()) return Result.error(403, "仅管理员可审核举报");
        return Result.success(jdbcTemplate.queryForList("SELECT r.id, r.reason, r.detail, r.status, r.create_time AS createTime, p.id AS postId, p.title, "
                + "ru.nickname AS reporterName, ru.username AS reporterUsername, "
                + "au.nickname AS reportedName, au.username AS reportedUsername "
                + "FROM content_report r JOIN post p ON p.id = r.post_id "
                + "LEFT JOIN user ru ON ru.id = r.reporter_id "
                + "LEFT JOIN user au ON au.id = p.author_id "
                + "WHERE r.status = ? ORDER BY r.create_time ASC LIMIT 100", status));
    }

    @GetMapping("/moderation/reports/{id}")
    public Result<Map<String, Object>> reportDetail(@PathVariable Long id) {
        if (!SecurityUtils.isAdmin()) return Result.error(403, "仅管理员可审核举报");
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "SELECT r.id, r.reason, r.detail, r.status, r.create_time AS createTime, "
                        + "p.id AS postId, p.title, p.create_time AS postCreateTime, "
                        + "ru.nickname AS reporterName, ru.username AS reporterUsername, "
                        + "au.nickname AS reportedName, au.username AS reportedUsername "
                        + "FROM content_report r JOIN post p ON p.id = r.post_id "
                        + "LEFT JOIN user ru ON ru.id = r.reporter_id "
                        + "LEFT JOIN user au ON au.id = p.author_id WHERE r.id = ?", id);
        if (records.isEmpty()) return Result.error("举报记录不存在");
        return Result.success(records.get(0));
    }

    /**
     * 结案：隐藏被举报内容或驳回举报。
     * <p>
     * 仅在判定隐藏时才连带通知作者并改 {@code privacy}，驳回不打扰作者——
     * 驳回是审核结论，不是对作者的处罚。
     * </p>
     * <p>
     * <b>注意这里改的是 {@code privacy} 而非 {@code is_deleted}，原因见类头注释。</b>
     * <p>
     * 另需注意：结案只把当前这条记录改成终态，同一用户对同一内容的
     * <b>其他</b> PENDING 记录不会被联动处理。但由于唯一键含 {@code status}，
     * 用户在旧记录结案后仍可再次提交举报——这是有意的，
     * 详见 {@link #report} 的说明。
     * </p>
     */
    @PostMapping("/moderation/reports/{id}")
    @AuditLog(module = "community", action = "resolve_report")
    public Result<Void> resolveReport(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        Long adminId = SecurityUtils.getUserId();
        if (!SecurityUtils.isAdmin()) return Result.error(403, "仅管理员可审核举报");
        List<Map<String, Object>> records = jdbcTemplate.queryForList("SELECT post_id AS postId FROM content_report WHERE id = ?", id);
        if (records.isEmpty()) return Result.error("举报记录不存在");
        boolean hide = Boolean.TRUE.equals(payload.get("hide"));
        String status = hide ? "RESOLVED" : "DISMISSED";
        jdbcTemplate.update("UPDATE content_report SET status = ?, handler_id = ?, handle_note = ?, handled_at = NOW() WHERE id = ?", status, adminId, payload.get("note"), id);
        if (hide) {
            Long postId = ((Number) records.get(0).get("postId")).longValue();
            Post post = postService.getById(postId);
            if (post != null) {
                jdbcTemplate.update("UPDATE post SET privacy = 1 WHERE id = ?", postId);
                notificationService.notify(new NotificationCommand(post.getAuthorId(), adminId, NotificationType.MODERATION,
                        NotificationTargetType.POST, postId, "你的内容已被限制可见", "管理员已根据举报处理该内容"));
            }
        }
        return Result.success(null);
    }

    @PostMapping("/moderation/post/{id}")
    @AuditLog(module = "community", action = "moderate_post")
    public Result<Void> moderatePost(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        if (!SecurityUtils.isAdmin()) return Result.error(403, "仅管理员可管理内容");
        Post post = postService.getById(id);
        if (post == null) return Result.error("内容不存在");
        Integer top = asFlag(payload.get("isTop"), post.getIsTop());
        Integer essence = asFlag(payload.get("isEssence"), post.getIsEssence());
        Integer privacy = asFlag(payload.get("privacy"), post.getPrivacy());
        jdbcTemplate.update("UPDATE post SET is_top = ?, is_essence = ?, privacy = ? WHERE id = ?", top, essence, privacy, id);
        return Result.success(null);
    }

    @GetMapping("/moderation/tags")
    public Result<List<Map<String, Object>>> tags() {
        if (!SecurityUtils.isAdmin()) return Result.error(403, "仅管理员可管理标签");
        return Result.success(communityTagService.all());
    }

    @PostMapping("/moderation/tags")
    @AuditLog(module = "community", action = "add_tag")
    public Result<Void> addTag(@RequestBody Map<String, String> payload) {
        Long userId = SecurityUtils.getUserId();
        if (!SecurityUtils.isAdmin()) return Result.error(403, "仅管理员可管理标签");
        String name = payload.get("name") == null ? "" : payload.get("name").trim();
        String group = payload.get("groupName") == null ? "自定义标签" : payload.get("groupName").trim();
        if (name.isEmpty() || name.length() > 64 || group.isEmpty()) return Result.error("标签名称或分组不合法");
        try { communityTagService.save(name, group, userId); } catch (org.springframework.dao.DuplicateKeyException e) { return Result.error("该标签已存在"); }
        return Result.success(null);
    }

    @PostMapping("/moderation/tags/{id}")
    @AuditLog(module = "community", action = "set_tag")
    public Result<Void> setTagEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        if (!SecurityUtils.isAdmin()) return Result.error(403, "仅管理员可管理标签");
        communityTagService.setEnabled(id, enabled);
        return Result.success(null);
    }

    /**
     * 把请求里的布尔开关转成表中的 tinyint 标志位。
     * <p>
     * <b>{@code null} 表示「不修改该字段」而非「置 0」：</b>
     * 治理接口一次可只调整置顶状态，用 {@code null} 保留原值，
     * 避免前端漏传字段时被误改。
     * 同时兼容布尔与 {@code "1"} 字符串两种入参，
     * 因为 {@code @RequestBody Map} 不会做类型转换。
     * </p>
     *
     * @param fallback 当前库中值，可能为 {@code null}
     * @return 0 或 1，绝不为 {@code null}
     */
    private Integer asFlag(Object value, Integer fallback) {
        if (value == null) return fallback == null ? 0 : fallback;
        return Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value)) ? 1 : 0;
    }
}
