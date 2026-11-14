package com.musealgo.oj.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.musealgo.oj.api.ai.UserAiUsageQuery;
import com.musealgo.oj.common.annotation.AuditLog;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.common.exception.ErrorResponses;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.dto.AdminUserBatchResult;
import com.musealgo.oj.dto.AdminUserView;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.error.SystemErrorCode;
import com.musealgo.oj.system.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 用户管理（管理员）：列表/详情/创建账号/角色调整/禁用/重置密码/批量操作/导出。 */
@Tag(name = "用户管理", description = "管理员用户管理")
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_EXPORT_ROWS = 10_000;
    private static final int MAX_REASON_LENGTH = 255;
    private static final Set<String> SORT_FIELDS = Set.of("id", "createTime", "lastLoginTime", "submitCount");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,32}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserService userService;
    private final UserAiUsageQuery aiUsageQuery;
    private final PasswordEncoder passwordEncoder;

    /** 与 oj-system 的 UserDetailsServiceImpl 同源开关：仅在规范化 RBAC 模式下同步关联表。 */
    @Value("${oj.security.normalized-rbac:false}")
    private boolean normalizedRbacEnabled;

    @Operation(summary = "用户列表")
    @GetMapping("/list")
    public Result<Page<AdminUserView>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer role,
            @RequestParam(required = false) Boolean banned,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {
        String invalid = validateListQuery(role, sortBy, order);
        if (invalid != null) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, invalid);
        }
        int safePage = Math.max(1, page == null ? 1 : page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size == null ? DEFAULT_PAGE_SIZE : size));

        LambdaQueryWrapper<User> wrapper = buildFilter(keyword, role, banned);
        applySort(wrapper, sortBy, "asc".equalsIgnoreCase(order));
        Page<User> raw = userService.page(new Page<>(safePage, safeSize), wrapper);

        Map<Long, Long> aiTokens = aiUsageQuery.totalTokensByUsers(idsOf(raw.getRecords()));
        Page<AdminUserView> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(raw.getRecords().stream()
                .map(user -> toView(user, aiTokens.getOrDefault(user.getId(), 0L), false))
                .toList());
        return Result.success(result);
    }

    @Operation(summary = "用户详情")
    @GetMapping("/{id}")
    public Result<AdminUserView> detail(@PathVariable Long id) {
        User user = userService.getById(id);
        if (user == null || Integer.valueOf(1).equals(user.getIsDeleted())) {
            return ErrorResponses.failure(SystemErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        Map<Long, Long> aiTokens = aiUsageQuery.totalTokensByUsers(List.of(id));
        return Result.success(toView(user, aiTokens.getOrDefault(id, 0L), true));
    }

    @Operation(summary = "调整角色")
    @AuditLog(module = "user", action = "update_role")
    @PostMapping("/{id}/role")
    @Transactional(rollbackFor = Exception.class)
    public Result<String> updateRole(@PathVariable Long id, @RequestBody RoleRequest request) {
        if (request == null || request.getRole() == null || request.getRole() < 1 || request.getRole() > 3) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "角色取值 1=学生 2=老师 3=管理员");
        }
        List<Long> enabledAdminIds = userService.lockEnabledAdminIds();
        User target = userService.getById(id);
        if (target == null || Integer.valueOf(1).equals(target.getIsDeleted())) {
            return ErrorResponses.failure(SystemErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        if (id.equals(SecurityUtils.getUserId()) && request.getRole() != 3) {
            return ErrorResponses.failure(SystemErrorCode.ADMIN_SELF_DEMOTION_NOT_ALLOWED, "不能降低自己的管理员角色");
        }
        // 只有当前生效的启用管理员被降级时才占用“最后一个管理员”名额；
        // 已封禁或封禁已过期的账号不应阻止其他管理员的正常调整。
        boolean targetIsEnabledAdmin = target.getRole() != null && target.getRole() == 3
                && enabledAdminIds.contains(id);
        if (targetIsEnabledAdmin && request.getRole() != 3 && enabledAdminIds.size() <= 1) {
            return ErrorResponses.failure(SystemErrorCode.ADMIN_LAST_ADMIN_REQUIRED, "系统必须保留至少一个管理员");
        }
        if (target.getRole() != null && target.getRole().equals(request.getRole())) {
            syncNormalizedRole(id, target.getRole());
            return Result.success("角色未变化");
        }
        User user = new User();
        user.setId(id);
        user.setRole(request.getRole());
        if (!userService.updateById(user)) throw new IllegalStateException("用户角色更新失败");
        syncNormalizedRole(id, request.getRole());
        return Result.success("角色已更新");
    }

    @Operation(summary = "禁用/启用")
    @AuditLog(module = "user", action = "ban_user")
    @PostMapping("/{id}/ban")
    @Transactional(rollbackFor = Exception.class)
    public Result<String> setBanned(@PathVariable Long id, @RequestBody BanRequest request) {
        if (request == null || request.getBanned() == null) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "缺少启用/禁用状态");
        }
        boolean banning = Boolean.TRUE.equals(request.getBanned());
        LocalDateTime now = LocalDateTime.now();
        String invalid = validateBanInput(banning, request.getReason(), request.getExpireTime(), now);
        if (invalid != null) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, invalid);
        }

        List<Long> enabledAdminIds = banning ? userService.lockEnabledAdminIds() : List.of();
        User target = userService.getById(id);
        if (target == null || Integer.valueOf(1).equals(target.getIsDeleted())) {
            return ErrorResponses.failure(SystemErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        if (banning && id.equals(SecurityUtils.getUserId())) {
            return ErrorResponses.failure(SystemErrorCode.ADMIN_SELF_BAN_NOT_ALLOWED, "不能禁用自己的账号");
        }
        boolean alreadyBanned = target.isBanActive(now);
        if (banning && !alreadyBanned && target.getRole() != null && target.getRole() == 3
                && enabledAdminIds.size() <= 1) {
            return ErrorResponses.failure(SystemErrorCode.ADMIN_LAST_ADMIN_REQUIRED, "不能禁用最后一个管理员");
        }
        if (!userService.update(banUpdate(id, banning, request.getReason(), request.getExpireTime()))) {
            throw new IllegalStateException("用户启用状态更新失败");
        }
        return Result.success(banning ? "已禁用该账号" : "已启用该账号");
    }

    @Operation(summary = "批量调整角色")
    @AuditLog(module = "user", action = "batch_update_role")
    @PostMapping("/batch/role")
    @Transactional(rollbackFor = Exception.class)
    public Result<AdminUserBatchResult> batchUpdateRole(@RequestBody BatchRoleRequest request) {
        if (request == null || request.getRole() == null || request.getRole() < 1 || request.getRole() > 3) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "角色取值 1=学生 2=老师 3=管理员");
        }
        List<Long> ids = normalizeIds(request.getIds());
        if (ids == null) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "请选择 1–100 个有效用户");
        }
        List<Long> enabledAdminIds = userService.lockEnabledAdminIds();
        List<User> targets = loadTargets(ids);
        if (targets == null) {
            return ErrorResponses.failure(SystemErrorCode.USER_NOT_FOUND, "部分用户不存在或已被删除");
        }
        if (request.getRole() != 3 && ids.contains(SecurityUtils.getUserId())) {
            return ErrorResponses.failure(SystemErrorCode.ADMIN_SELF_DEMOTION_NOT_ALLOWED, "不能降低自己的管理员角色");
        }
        if (request.getRole() != 3 && targets.stream()
                .anyMatch(target -> target.getRole() != null && target.getRole() == 3)) {
            List<Long> remaining = new ArrayList<>(enabledAdminIds);
            remaining.removeAll(ids);
            if (remaining.isEmpty()) {
                return ErrorResponses.failure(SystemErrorCode.ADMIN_LAST_ADMIN_REQUIRED, "系统必须保留至少一个管理员");
            }
        }
        for (User target : targets) {
            User patch = new User();
            patch.setId(target.getId());
            patch.setRole(request.getRole());
            if (!userService.updateById(patch)) throw new IllegalStateException("用户角色更新失败");
            syncNormalizedRole(target.getId(), request.getRole());
        }
        return Result.success(new AdminUserBatchResult(ids.size(), targets.size()));
    }

    @Operation(summary = "批量禁用/启用")
    @AuditLog(module = "user", action = "batch_ban_user")
    @PostMapping("/batch/ban")
    @Transactional(rollbackFor = Exception.class)
    public Result<AdminUserBatchResult> batchSetBanned(@RequestBody BatchBanRequest request) {
        if (request == null || request.getBanned() == null) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "缺少启用/禁用状态");
        }
        List<Long> ids = normalizeIds(request.getIds());
        if (ids == null) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "请选择 1–100 个有效用户");
        }
        boolean banning = Boolean.TRUE.equals(request.getBanned());
        LocalDateTime now = LocalDateTime.now();
        String invalid = validateBanInput(banning, request.getReason(), request.getExpireTime(), now);
        if (invalid != null) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, invalid);
        }
        List<Long> enabledAdminIds = banning ? userService.lockEnabledAdminIds() : List.of();
        List<User> targets = loadTargets(ids);
        if (targets == null) {
            return ErrorResponses.failure(SystemErrorCode.USER_NOT_FOUND, "部分用户不存在或已被删除");
        }
        if (banning && ids.contains(SecurityUtils.getUserId())) {
            return ErrorResponses.failure(SystemErrorCode.ADMIN_SELF_BAN_NOT_ALLOWED, "不能禁用自己的账号");
        }
        if (banning && targets.stream().anyMatch(target -> target.getRole() != null && target.getRole() == 3)) {
            List<Long> remaining = new ArrayList<>(enabledAdminIds);
            remaining.removeAll(ids);
            if (remaining.isEmpty()) {
                return ErrorResponses.failure(SystemErrorCode.ADMIN_LAST_ADMIN_REQUIRED, "不能禁用最后一个管理员");
            }
        }
        for (Long id : ids) {
            if (!userService.update(banUpdate(id, banning, request.getReason(), request.getExpireTime()))) {
                throw new IllegalStateException("用户启用状态更新失败");
            }
        }
        return Result.success(new AdminUserBatchResult(ids.size(), targets.size()));
    }

    @Operation(summary = "创建账号（管理员代建）")
    @AuditLog(module = "user", action = "create_user")
    @PostMapping("/create")
    @Transactional(rollbackFor = Exception.class)
    public Result<String> createAccount(@RequestBody CreateAccountRequest request) {
        String invalid = validateCreate(request);
        if (invalid != null) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, invalid);
        }
        Integer role = request.getRole() == null ? 1 : request.getRole();
        User user = new User();
        user.setUsername(request.getUsername().trim());
        user.setNickname(request.getNickname().trim());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(role.byteValue());
        user.setIsBanned((byte) 0);
        if (StringUtils.hasText(request.getEmail())) {
            user.setEmail(request.getEmail().trim());
        }
        if (StringUtils.hasText(request.getSchool())) {
            user.setSchool(request.getSchool().trim());
        }
        if (StringUtils.hasText(request.getRealName())) {
            user.setRealName(request.getRealName().trim());
        }
        Integer check = userService.registerCheck(user);
        if (check == 1) {
            return ErrorResponses.failure(SystemErrorCode.AUTH_USERNAME_TAKEN, "用户名已被使用");
        }
        if (check == 2) {
            return ErrorResponses.failure(SystemErrorCode.AUTH_EMAIL_TAKEN, "邮箱已被使用");
        }
        // 注意：不能用 userService.register()——它会强制把角色改成学生(1)，管理员代建教师/管理员账号会被静默降级
        try {
            if (!userService.save(user)) {
                return ErrorResponses.failure(CommonErrorCode.INTERNAL_ERROR, "账号创建失败");
            }
        } catch (DuplicateKeyException duplicate) {
            // 预检查与写入之间仍有并发窗口，数据库唯一索引是最终防线。
            return ErrorResponses.failure(SystemErrorCode.AUTH_USERNAME_TAKEN, "用户名或邮箱已被使用");
        }
        syncNormalizedRole(user.getId(), user.getRole());
        return Result.success("账号创建成功");
    }

    @Operation(summary = "重置密码（管理员直接设置新密码）")
    @AuditLog(module = "user", action = "reset_password")
    @PostMapping("/{id}/reset-password")
    @Transactional(rollbackFor = Exception.class)
    public Result<String> resetPassword(@PathVariable Long id, @RequestBody ResetPasswordRequest request) {
        if (request == null || !StringUtils.hasText(request.getNewPassword())) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "新密码不能为空");
        }
        if (request.getNewPassword().length() < 6 || request.getNewPassword().length() > 32) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "新密码长度需为 6–32 位");
        }
        User target = userService.getById(id);
        if (target == null || Integer.valueOf(1).equals(target.getIsDeleted())) {
            return ErrorResponses.failure(SystemErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        if (!userService.updatePassword(id, passwordEncoder.encode(request.getNewPassword()))) {
            return ErrorResponses.failure(CommonErrorCode.INTERNAL_ERROR, "密码重置失败");
        }
        return Result.success("密码已重置");
    }

    @Operation(summary = "导出用户 CSV")
    @AuditLog(module = "user", action = "export_users")
    @GetMapping("/export")
    public ResponseEntity<?> export(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer role,
            @RequestParam(required = false) Boolean banned,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {
        String invalid = validateListQuery(role, sortBy, order);
        if (invalid != null) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, invalid));
        }
        LambdaQueryWrapper<User> wrapper = buildFilter(keyword, role, banned);
        applySort(wrapper, sortBy, "asc".equalsIgnoreCase(order));
        // 固定上限，避免一次导出把整库用户和跨模块统计全部拉进内存。
        List<User> users = userService.list(wrapper.last("LIMIT " + MAX_EXPORT_ROWS));
        Map<Long, Long> aiTokens = aiUsageQuery.totalTokensByUsers(idsOf(users));
        String csv = buildCsv(users, aiTokens);
        byte[] body = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("admin-users-" + LocalDateTime.now().format(FILE_TIME) + ".csv")
                .build());
        headers.setContentLength(body.length);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private static String validateListQuery(Integer role, String sortBy, String order) {
        if (role != null && (role < 1 || role > 3)) {
            return "角色取值 1=学生 2=老师 3=管理员";
        }
        if (!SORT_FIELDS.contains(sortBy)) {
            return "不支持的排序字段";
        }
        if (!"asc".equalsIgnoreCase(order) && !"desc".equalsIgnoreCase(order)) {
            return "排序方向只能是 asc 或 desc";
        }
        return null;
    }

    private static LambdaQueryWrapper<User> buildFilter(String keyword, Integer role, Boolean banned) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        // 历史数据可能没有 is_deleted 值；按未删除处理，避免把老账号全部隐藏。
        wrapper.and(w -> w.eq(User::getIsDeleted, 0).or().isNull(User::getIsDeleted));
        if (role != null) {
            wrapper.eq(User::getRole, role);
        }
        LocalDateTime now = LocalDateTime.now();
        if (Boolean.TRUE.equals(banned)) {
            wrapper.eq(User::getIsBanned, 1)
                    .and(w -> w.isNull(User::getBanExpireTime).or().gt(User::getBanExpireTime, now));
        } else if (Boolean.FALSE.equals(banned)) {
            wrapper.and(w -> w.and(x -> x.ne(User::getIsBanned, 1).or().isNull(User::getIsBanned))
                    .or(x -> x.isNotNull(User::getBanExpireTime).le(User::getBanExpireTime, now)));
        }
        String trimmed = keyword == null ? "" : keyword.trim();
        if (!trimmed.isEmpty()) {
            String escaped = escapeLike(trimmed);
            Long id = parseId(trimmed);
            wrapper.and(w -> {
                w.like(User::getUsername, escaped)
                        .or().like(User::getNickname, escaped)
                        .or().like(User::getRealName, escaped)
                        .or().like(User::getEmail, escaped);
                if (id != null) {
                    w.or().eq(User::getId, id);
                }
            });
        }
        return wrapper;
    }

    private static void applySort(LambdaQueryWrapper<User> wrapper, String sortBy, boolean asc) {
        switch (sortBy) {
            case "createTime" -> {
                if (asc) wrapper.orderByAsc(User::getCreateTime);
                else wrapper.orderByDesc(User::getCreateTime);
            }
            case "lastLoginTime" -> {
                if (asc) wrapper.orderByAsc(User::getLastLoginTime);
                else wrapper.orderByDesc(User::getLastLoginTime);
            }
            case "submitCount" -> {
                if (asc) wrapper.orderByAsc(User::getSubmitCount);
                else wrapper.orderByDesc(User::getSubmitCount);
            }
            default -> {
                if (asc) wrapper.orderByAsc(User::getId);
                else wrapper.orderByDesc(User::getId);
            }
        }
        if (!"id".equals(sortBy)) {
            wrapper.orderByDesc(User::getId);
        }
    }

    private static AdminUserView toView(User user, long aiTokens, boolean detail) {
        LocalDateTime now = LocalDateTime.now();
        AdminUserView view = new AdminUserView();
        view.setId(user.getId());
        view.setUsername(user.getUsername());
        view.setNickname(user.getNickname());
        view.setRealName(user.getRealName());
        view.setEmail(user.getEmail());
        view.setRole(user.getRole());
        view.setSchool(user.getSchool());
        view.setIsBanned(user.getIsBanned() != null && user.getIsBanned() == 1);
        view.setBanActive(user.isBanActive(now));
        view.setBanReason(user.getBanReason());
        view.setBanExpireTime(user.getBanExpireTime());
        view.setSubmissionCount(user.getSubmitCount() == null ? 0 : user.getSubmitCount());
        view.setAcceptedCount(user.getAcceptedCount() == null ? 0 : user.getAcceptedCount());
        view.setRating(user.getRating());
        view.setAiTokens(aiTokens);
        view.setCreateTime(user.getCreateTime());
        view.setLastLoginTime(user.getLastLoginTime());
        if (detail) {
            view.setMajor(user.getMajor());
            view.setAvatar(user.getAvatar());
            view.setIntroduction(user.getIntroduction());
            view.setLevel(user.getLevel());
            view.setExperience(user.getExperience());
            view.setFollowerCount(user.getFollowerCount());
            view.setFollowingCount(user.getFollowingCount());
            view.setLikeCount(user.getLikeCount());
        }
        return view;
    }

    private static String buildCsv(List<User> users, Map<Long, Long> aiTokens) {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",",
                "ID", "用户名", "昵称", "真实姓名", "邮箱", "角色", "状态", "封禁原因", "封禁到期",
                "学校", "提交数", "通过数", "Rating", "AI 用量", "注册时间", "最后登录")).append('\n');
        for (User user : users) {
            long tokens = aiTokens.getOrDefault(user.getId(), 0L);
            csv.append(String.join(",",
                    csvCell(user.getId()),
                    csvCell(user.getUsername()),
                    csvCell(user.getNickname()),
                    csvCell(user.getRealName()),
                    csvCell(user.getEmail()),
                    csvCell(roleLabel(user.getRole())),
                    csvCell(statusLabel(user, now)),
                    csvCell(user.getBanReason()),
                    csvCell(user.getBanExpireTime() == null ? "" : user.getBanExpireTime().format(CSV_TIME)),
                    csvCell(user.getSchool()),
                    csvCell(user.getSubmitCount() == null ? 0 : user.getSubmitCount()),
                    csvCell(user.getAcceptedCount() == null ? 0 : user.getAcceptedCount()),
                    csvCell(user.getRating()),
                    csvCell(tokens),
                    csvCell(user.getCreateTime() == null ? "" : user.getCreateTime().format(CSV_TIME)),
                    csvCell(user.getLastLoginTime() == null ? "" : user.getLastLoginTime().format(CSV_TIME))
            )).append('\n');
        }
        return csv.toString();
    }

    private static String roleLabel(Byte role) {
        if (role == null) return "未知";
        return switch (role) {
            case 1 -> "学生";
            case 2 -> "教师";
            case 3 -> "管理员";
            default -> "未知";
        };
    }

    private static String statusLabel(User user, LocalDateTime now) {
        if (user.isBanActive(now)) return "已禁用";
        if (user.getIsBanned() != null && user.getIsBanned() == 1) return "封禁已过期";
        return "正常";
    }

    /** CSV 单元格：统一加引号，转义双引号，并阻断公式注入。 */
    private static String csvCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        text = text.replace('\r', ' ').replace('\n', ' ');
        if (!text.isEmpty() && "=+-@\t".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static String validateCreate(CreateAccountRequest request) {
        if (request == null || !StringUtils.hasText(request.getUsername())) {
            return "用户名不能为空";
        }
        if (!USERNAME_PATTERN.matcher(request.getUsername().trim()).matches()) {
            return "用户名需为 3–32 位字母、数字或下划线";
        }
        if (!StringUtils.hasText(request.getNickname())) {
            return "昵称不能为空";
        }
        if (request.getNickname().trim().length() > 32) {
            return "昵称不能超过 32 位";
        }
        if (!StringUtils.hasText(request.getPassword())) {
            return "密码不能为空";
        }
        if (request.getPassword().length() < 6 || request.getPassword().length() > 32) {
            return "密码长度需为 6–32 位";
        }
        if (request.getRole() != null && (request.getRole() < 1 || request.getRole() > 3)) {
            return "角色取值 1=学生 2=老师 3=管理员";
        }
        if (StringUtils.hasText(request.getEmail())) {
            String email = request.getEmail().trim();
            if (email.length() > 64 || !EMAIL_PATTERN.matcher(email).matches()) {
                return "邮箱格式不正确";
            }
        }
        if (StringUtils.hasText(request.getSchool()) && request.getSchool().trim().length() > 64) {
            return "学校不能超过 64 位";
        }
        if (StringUtils.hasText(request.getRealName()) && request.getRealName().trim().length() > 32) {
            return "真实姓名不能超过 32 位";
        }
        return null;
    }

    private static String validateBanInput(boolean banning, String reason, LocalDateTime expireTime,
                                           LocalDateTime now) {
        if (!banning) {
            return null;
        }
        if (reason != null && reason.trim().length() > MAX_REASON_LENGTH) {
            return "封禁原因不能超过 255 位";
        }
        if (expireTime != null && !expireTime.isAfter(now)) {
            return "封禁到期时间必须晚于当前时间";
        }
        return null;
    }

    private static UpdateWrapper<User> banUpdate(Long id, boolean banning, String reason,
                                                 LocalDateTime expireTime) {
        // 这里用列名而不是 Lambda：解封需要把字段显式写成 NULL，
        // 且该 helper 在无 Spring 容器的单元测试中也要能构造。
        return new UpdateWrapper<User>()
                .eq("id", id)
                .set("is_banned", banning ? (byte) 1 : (byte) 0)
                .set("ban_reason", banning && StringUtils.hasText(reason) ? reason.trim() : null)
                .set("ban_expire_time", banning ? expireTime : null);
    }

    private List<User> loadTargets(List<Long> ids) {
        List<User> users = userService.listByIds(ids);
        if (users == null || users.size() != ids.size()) {
            return null;
        }
        if (users.stream().anyMatch(user -> Integer.valueOf(1).equals(user.getIsDeleted()))) {
            return null;
        }
        return users;
    }

    private static List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_BATCH_SIZE) {
            return null;
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                return null;
            }
            unique.add(id);
        }
        return unique.isEmpty() || unique.size() > MAX_BATCH_SIZE ? null : List.copyOf(unique);
    }

    private void syncNormalizedRole(Long userId, Byte role) {
        if (normalizedRbacEnabled && !userService.syncNormalizedRole(userId, role)) {
            throw new IllegalStateException("角色已写入，但规范化权限关联同步失败");
        }
    }

    private static List<Long> idsOf(List<User> users) {
        return users.stream().map(User::getId).collect(Collectors.toList());
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Long parseId(String keyword) {
        try {
            return Long.parseLong(keyword);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Data
    public static class RoleRequest {
        private Byte role;
    }

    @Data
    public static class BanRequest {
        private Boolean banned;
        private String reason;
        private LocalDateTime expireTime;
    }

    @Data
    public static class CreateAccountRequest {
        private String username;
        private String password;
        private String nickname;
        private Integer role;
        private String email;
        private String school;
        private String realName;
    }

    @Data
    public static class ResetPasswordRequest {
        private String newPassword;
    }

    @Data
    public static class BatchRoleRequest {
        private List<Long> ids;
        private Byte role;
    }

    @Data
    public static class BatchBanRequest {
        private List<Long> ids;
        private Boolean banned;
        private String reason;
        private LocalDateTime expireTime;
    }
}
