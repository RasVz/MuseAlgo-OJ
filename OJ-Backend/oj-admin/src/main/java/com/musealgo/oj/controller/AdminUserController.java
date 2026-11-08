package com.musealgo.oj.controller;

import com.musealgo.oj.common.exception.ErrorResponses;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.system.error.SystemErrorCode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.annotation.AuditLog;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.api.submission.UserSubmissionMetricsQuery;
import com.musealgo.oj.api.ai.UserAiUsageQuery;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.service.UserService;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.crypto.password.PasswordEncoder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;

/** 用户管理（管理员）：列表/搜索/创建账号/角色调整/禁用/重置密码。 */
@Tag(name = "用户管理", description = "管理员用户管理")
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;
    private final UserSubmissionMetricsQuery submissionMetricsQuery;
    private final UserAiUsageQuery aiUsageQuery;
    private final PasswordEncoder passwordEncoder;

    /** 与 oj-system 的 UserDetailsServiceImpl 同源开关：仅在规范化 RBAC 模式下同步关联表。 */
    @Value("${oj.security.normalized-rbac:false}")
    private boolean normalizedRbacEnabled;

    @Operation(summary = "用户列表")
    @GetMapping("/list")
    public Result<Page<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "11") Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {
        boolean asc = "asc".equalsIgnoreCase(order);
        page = Math.max(1, page);
        size = Math.max(1, size);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(User::getUsername, keyword)
                    .or().like(User::getNickname, keyword)
                    .or().eq(User::getId, parseId(keyword)));
        }

        // 提交数/AI 用量来自跨模块统计查询，无法走 SQL 排序：取全量过滤集，算完指标后内存排序再分页
        if ("submissionCount".equals(sortBy) || "aiTokens".equals(sortBy)) {
            List<User> matched = userService.list(wrapper);
            List<Long> ids = toIds(matched);
            Map<Long, Long> submissionCounts = submissionMetricsQuery.countByUsers(ids);
            Map<Long, Long> aiTokens = aiUsageQuery.totalTokensByUsers(ids);
            Comparator<User> byMetric = "aiTokens".equals(sortBy)
                    ? Comparator.comparingLong(u -> aiTokens.getOrDefault(u.getId(), 0L))
                    : Comparator.comparingLong(u -> submissionCounts.getOrDefault(u.getId(), 0L));
            matched.sort((asc ? byMetric : byMetric.reversed())
                    .thenComparing(User::getId, Comparator.reverseOrder()));
            long total = matched.size();
            int from = (int) Math.min((long) (page - 1) * size, total);
            int to = (int) Math.min((long) from + size, total);
            return Result.success(pageResult(
                    toRows(matched.subList(from, to), submissionCounts, aiTokens), page, size, total));
        }

        if (asc) {
            wrapper.orderByAsc(User::getId);
        } else {
            wrapper.orderByDesc(User::getId);
        }
        Page<User> raw = userService.page(new Page<>(page, size), wrapper);

        Set<Long> userIds = raw.getRecords().stream().map(User::getId).collect(Collectors.toSet());
        Map<Long, Long> submissionCounts = submissionMetricsQuery.countByUsers(userIds);
        Map<Long, Long> aiTokens = aiUsageQuery.totalTokensByUsers(userIds);
        return Result.success(pageResult(
                toRows(raw.getRecords(), submissionCounts, aiTokens), raw.getCurrent(), raw.getSize(), raw.getTotal()));
    }

    private static List<Long> toIds(List<User> users) {
        return users.stream().map(User::getId).toList();
    }

    private static Page<Map<String, Object>> pageResult(List<Map<String, Object>> rows, long page, long size, long total) {
        Page<Map<String, Object>> result = new Page<>(page, size, total);
        result.setRecords(rows);
        return result;
    }

    private static List<Map<String, Object>> toRows(List<User> users, Map<Long, Long> submissionCounts, Map<Long, Long> aiTokens) {
        return users.stream().map(user -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", user.getId());
            row.put("username", user.getUsername());
            row.put("nickname", user.getNickname());
            row.put("role", user.getRole());
            row.put("school", user.getSchool());
            row.put("realName", user.getRealName());
            row.put("email", user.getEmail());
            row.put("createTime", user.getCreateTime());
            row.put("lastLoginTime", user.getLastLoginTime());
            row.put("isBanned", user.getIsBanned());
            row.put("submissionCount", submissionCounts.getOrDefault(user.getId(), 0L));
            row.put("aiTokens", aiTokens.getOrDefault(user.getId(), 0L));
            return row;
        }).toList();
    }

    @Operation(summary = "调整角色")
    @AuditLog(module = "user", action = "update_role")
    @PostMapping("/{id}/role")
    @Transactional(rollbackFor = Exception.class)
    public Result<String> updateRole(@PathVariable Long id, @RequestBody RoleRequest request) {
        if (request.getRole() == null || request.getRole() < 1 || request.getRole() > 3) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "角色取值 1=学生 2=老师 3=管理员");
        }
        List<Long> enabledAdminIds = userService.lockEnabledAdminIds();
        User target = userService.getById(id);
        if (target == null) {
            return ErrorResponses.failure(SystemErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        if (id.equals(SecurityUtils.getUserId()) && request.getRole() != 3) {
            return ErrorResponses.failure(SystemErrorCode.ADMIN_SELF_DEMOTION_NOT_ALLOWED, "不能降低自己的管理员角色");
        }
        if (target.getRole() != null && target.getRole() == 3 && request.getRole() != 3
                && enabledAdminIds.size() <= 1) {
            return ErrorResponses.failure(SystemErrorCode.ADMIN_LAST_ADMIN_REQUIRED, "系统必须保留至少一个管理员");
        }
        User user = new User();
        user.setId(id);
        user.setRole(request.getRole());
        if (!userService.updateById(user)) throw new IllegalStateException("用户角色更新失败");
        // 规范化 RBAC 模式下 user.role 不再是授权来源，必须同步 sys_user_role，
        // 否则改角色不生效（旧关联继续生效），甚至形成本应撤权却仍保留权限的落差。
        if (normalizedRbacEnabled && !userService.syncNormalizedRole(id, request.getRole())) {
            throw new IllegalStateException("角色已写入 user.role，但规范化权限关联同步失败");
        }
        return Result.success("角色已更新");
    }

    @Operation(summary = "禁用/启用")
    @AuditLog(module = "user", action = "ban_user")
    @PostMapping("/{id}/ban")
    @Transactional(rollbackFor = Exception.class)
    public Result<String> setBanned(@PathVariable Long id, @RequestBody BanRequest request) {
        List<Long> enabledAdminIds = userService.lockEnabledAdminIds();
        User target = userService.getById(id);
        if (target == null) {
            return ErrorResponses.failure(SystemErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        boolean banning = Boolean.TRUE.equals(request.getBanned());
        if (banning && id.equals(SecurityUtils.getUserId())) {
            return ErrorResponses.failure(SystemErrorCode.ADMIN_SELF_BAN_NOT_ALLOWED, "不能禁用自己的账号");
        }
        if (banning && target.getRole() != null && target.getRole() == 3 && enabledAdminIds.size() <= 1) {
            return ErrorResponses.failure(SystemErrorCode.ADMIN_LAST_ADMIN_REQUIRED, "不能禁用最后一个管理员");
        }
        User user = new User();
        user.setId(id);
        user.setIsBanned(Boolean.TRUE.equals(request.getBanned()) ? (byte) 1 : (byte) 0);
        if (!userService.updateById(user)) throw new IllegalStateException("用户启用状态更新失败");
        return Result.success(Boolean.TRUE.equals(request.getBanned()) ? "已禁用该账号" : "已启用该账号");
    }

    @Operation(summary = "创建账号（管理员代建）")
    @AuditLog(module = "user", action = "create_user")
    @PostMapping("/create")
    @Transactional(rollbackFor = Exception.class)
    public Result<String> createAccount(@RequestBody CreateAccountRequest request) {
        if (request == null || !StringUtils.hasText(request.getUsername())) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "用户名不能为空");
        }
        if (!StringUtils.hasText(request.getPassword()) || request.getPassword().length() < 6) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "密码至少 6 位");
        }
        if (!StringUtils.hasText(request.getNickname())) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "昵称不能为空");
        }
        Integer role = request.getRole() == null ? 1 : request.getRole();
        if (role < 1 || role > 3) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "角色取值 1=学生 2=老师 3=管理员");
        }
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
        if (!userService.save(user)) {
            return ErrorResponses.failure(CommonErrorCode.INTERNAL_ERROR, "账号创建失败");
        }
        // 规范化 RBAC 模式下新账号必须有 sys_user_role 关联，否则 fail-closed 判为无权限。
        if (normalizedRbacEnabled && !userService.syncNormalizedRole(user.getId(), user.getRole())) {
            throw new IllegalStateException("账号已创建，但规范化权限关联同步失败");
        }
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
        if (request.getNewPassword().length() < 6) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "新密码至少 6 位");
        }
        User target = userService.getById(id);
        if (target == null) {
            return ErrorResponses.failure(SystemErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        if (!userService.updatePassword(id, passwordEncoder.encode(request.getNewPassword()))) {
            return ErrorResponses.failure(CommonErrorCode.INTERNAL_ERROR, "密码重置失败");
        }
        return Result.success("密码已重置");
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
}
