package top.everblue.oj.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.annotation.AuditLog;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.system.entity.vo.UserVO;
import top.everblue.oj.community.service.UserRelationService;
import top.everblue.oj.judge.service.SubmissionService;
import top.everblue.oj.system.service.UserService;
import top.everblue.oj.analytics.application.TagRatingService;
import top.everblue.oj.analytics.entity.Tag;
import top.everblue.oj.analytics.entity.UserTagRating;
import top.everblue.oj.api.problem.ProblemDigest;
import top.everblue.oj.api.problem.ProblemQuery;
import top.everblue.oj.api.profile.ProfileQuery;
import top.everblue.oj.judge.application.SubmissionApplicationService;
import top.everblue.oj.common.annotation.RateLimit;
import top.everblue.oj.common.utils.SecurityUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Set;
import java.util.HashSet;

/**
 * 用户主页与个人设置聚合接口：把用户资料、偏好、排行榜、六维雷达、推荐题、学习计划、错题本串成对外的 HTTP 契约。
 * <p>
 * 放在 oj-admin 而不是 oj-system，是因为这些接口天然<strong>跨模块编排</strong>——雷达图要读
 * analytics 的标签评分，推荐题要读 problem 的题目摘要与 judge 的提交记录。
 * oj-system 只拥有用户表本身，不该反向依赖下游模块；拆开依赖方向，是为了不让「改一个首页卡片」
 * 变成对 oj-system 的侵入式修改。
 * </p>
 * <p>
 * <strong>隐私判定是这个类的第一职责。</strong> 每个读取他人数据的接口开头都必须过
 * {@code canViewProfile}，它对未登录访问者检查对方是否关闭了主页公开。新增接口时容易漏掉这一步，
 * 漏掉的后果是把「用户设为私密的主页数据」直接暴露出去，且不会有任何报错提示。
 * </p>
 * <p>
 * 主页信息优先取 user 表上缓存好的计数字段（followerCount / submitCount / rating 等），
 * 而不是每次 COUNT ——个人主页是高频访问且 QPS 最高的页面之一，逐个统计会把数据库打满。
 * 代价是计数有延迟，这是刻意的取舍。
 * </p>
 * <p>
 * 错题本相关逻辑用 {@link JdbcTemplate} 手写 SQL 而非走 MyBatis-Plus：
 * 它们依赖 {@code INSERT ... ON DUPLICATE KEY UPDATE} 与多表 JOIN 的聚合子查询，
 * 硬塞进 ORM 只会更难读。这类 SQL 改动风险高，改之前务必在预发库上核对影响行数。
 * </p>
 */
@RestController
@RequestMapping("/user")
public class UserController {
    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TagRatingService tagRatingService;

    @Autowired
    private ProblemQuery problemQuery;
    @Autowired
    private ProfileQuery profileQuery;

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private SubmissionApplicationService submissionApplicationService;

    @Autowired
    private UserRelationService userRelationService;

    @Autowired
    private ObjectMapper objectMapper;

    private Long getCurrentUserId() {
        try {
            org.springframework.security.core.Authentication authentication =
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof User) {
                return ((User) authentication.getPrincipal()).getId();
            }
        } catch (Exception e) {

        }
        return null;
    }

    /** 主页是否对未登录用户公开（preferences.profilePublic 默认 true） */
    private boolean isProfilePublic(Long targetUserId) {
        try {
            Integer hidden = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user WHERE id = ? AND JSON_UNQUOTE(JSON_EXTRACT(preferences, '$.profilePublic')) = 'false'",
                    Integer.class, targetUserId);
            return hidden == null || hidden == 0;
        } catch (Exception e) {
            return true;
        }
    }

    /** 未登录访问他人主页时，校验对方是否公开主页 */
    private boolean canViewProfile(Long targetUserId) {
        return getCurrentUserId() != null || isProfilePublic(targetUserId);
    }

    private Map<String, Object> buildDefaultPreferences() {
        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("language", "cpp");
        preferences.put("fontSize", 14);
        preferences.put("autoComplete", true);
        preferences.put("editorTheme", "vs-light");
        preferences.put("showLineNumbers", true);
        preferences.put("shareSubmissions", true);
        preferences.put("profilePublic", true);
        preferences.put("notifications", Map.of(
                "interactions", true,
                "homework", true,
                "contest", true));
        return preferences;
    }

    private int parseFontSize(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            int fontSize = Integer.parseInt(String.valueOf(value));
            return Math.max(12, Math.min(fontSize, 24));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean parseAutoComplete(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, Object> normalizeNotifications(Object value) {
        Map<String, Object> defaults = Map.of(
                "interactions", true,
                "homework", true,
                "contest", true);
        if (!(value instanceof Map<?, ?> source)) {
            return defaults;
        }
        Map<String, Object> notifications = new LinkedHashMap<>(defaults);
        notifications.put("interactions", parseBoolean(source.get("interactions"), true));
        notifications.put("homework", parseBoolean(source.get("homework"), true));
        notifications.put("contest", parseBoolean(source.get("contest"), true));
        return notifications;
    }

    private Map<String, Object> normalizePreferences(Map<String, Object> source) {
        Map<String, Object> preferences = buildDefaultPreferences();
        if (source == null) {
            return preferences;
        }

        String language = source.get("language") == null ? null : String.valueOf(source.get("language")).trim();
        if ("cpp".equals(language) || "java".equals(language) || "python".equals(language)) {
            preferences.put("language", language);
        }

        preferences.put("fontSize", parseFontSize(source.get("fontSize"), 14));
        preferences.put("autoComplete", parseAutoComplete(source.get("autoComplete"), true));

        String theme = source.get("editorTheme") == null ? null : String.valueOf(source.get("editorTheme")).trim();
        if ("vs-light".equals(theme) || "vs-dark".equals(theme)) {
            preferences.put("editorTheme", theme);
        }

        preferences.put("showLineNumbers", parseBoolean(source.get("showLineNumbers"), true));
        preferences.put("shareSubmissions", parseBoolean(source.get("shareSubmissions"), true));
        preferences.put("profilePublic", parseBoolean(source.get("profilePublic"), true));
        preferences.put("notifications", normalizeNotifications(source.get("notifications")));
        return preferences;
    }

    @GetMapping("/info/{id}")
    public Result<UserVO> getUserInfo(@PathVariable Long id) {
        if (!canViewProfile(id)) {
            return Result.error(403, "该用户未公开主页，登录后可查看");
        }
        // 1. 查询基本信息
        User user = userService.getById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 2. 转换 VO
        UserVO vo = new UserVO();
        // BeanUtils 会自动拷贝同名属性 (username, nickname, avatar, school, blog, github 等)
        BeanUtils.copyProperties(user, vo);

        // 手动映射名称不一致或需要特殊处理的字段
        vo.setMotto(user.getMotto());

        // === 3. 核心优化：直接使用数据库缓存的统计数据 ===
        // 避免了两次 COUNT 查询，性能极大提升
        // 注意判空，防止数据库默认为 NULL 导致空指针
        vo.setFollowerCount(user.getFollowerCount() != null ? user.getFollowerCount().longValue() : 0L);
        vo.setFollowingCount(user.getFollowingCount() != null ? user.getFollowingCount().longValue() : 0L);
        vo.setLikeCount(user.getLikeCount() != null ? user.getLikeCount().longValue() : 0L);

        // 提交数、通过数、Rating 也直接拿
        vo.setSubmitCount(user.getSubmitCount());
        vo.setAcceptedCount(user.getAcceptedCount());
        vo.setRating(user.getRating());

        // === 4. 查询“是否已关注” (这个无法缓存，必须查) ===
        Long currentUserId = getCurrentUserId();
        if (currentUserId != null) {
            // 只有登录状态下，且看的不是自己，才去查是否关注
            if (!currentUserId.equals(id)) {
                boolean isFollowed = userRelationService.isFollowing(currentUserId, id);
                vo.setIsFollowed(isFollowed);
            } else {
                // 看自己，默认为 false 或 true 均可，看前端需求，通常由前端判断 IDs 是否相等来隐藏关注按钮
                vo.setIsFollowed(false);
            }
        } else {
            // 未登录
            vo.setIsFollowed(false);
        }

        return Result.success(vo);
    }

    @PutMapping("/profile")
    public Result<User> updateProfile(@RequestBody User profile) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return Result.fail("未登录或登录状态已失效");
        }

        if (profile == null) {
            return Result.fail("请求参数不能为空");
        }

        // 邮箱绑定壳子：先做唯一性校验（暂不发送验证邮件）
        if (profile.getEmail() != null && !profile.getEmail().trim().isEmpty()) {
            String email = profile.getEmail().trim();
            boolean taken = userService.lambdaQuery()
                    .eq(User::getEmail, email)
                    .ne(User::getId, currentUserId)
                    .exists();
            if (taken) {
                return Result.fail("该邮箱已被其他账号使用");
            }
        }

        boolean success = userService.updateProfile(currentUserId, profile);
        if (!success) {
            return Result.fail("个人资料更新失败");
        }

        User updatedUser = userService.getById(currentUserId);
        if (updatedUser == null) {
            return Result.fail("用户不存在");
        }
        updatedUser.setPassword(null);

        return Result.success(updatedUser);
    }

    @AuditLog(module = "user", action = "change_password")
    @PutMapping("/password")
    public Result<String> changePassword(@RequestBody Map<String, String> body) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return Result.fail("未登录或登录状态已失效");
        }

        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        String confirmPassword = body.get("confirmPassword");

        if (oldPassword == null || oldPassword.trim().isEmpty()) {
            return Result.fail("当前密码不能为空");
        }
        if (newPassword == null || newPassword.trim().isEmpty()) {
            return Result.fail("新密码不能为空");
        }
        if (newPassword.length() < 6) {
            return Result.fail("新密码长度不能少于6位");
        }
        if (confirmPassword == null || !newPassword.equals(confirmPassword)) {
            return Result.fail("两次输入的新密码不一致");
        }

        boolean success = userService.changePassword(currentUserId, oldPassword, newPassword);
        if (!success) {
            return Result.fail("当前密码错误");
        }

        return Result.success("密码修改成功");
    }

    @GetMapping("/preferences")
    public Result<Map<String, Object>> getPreferences() {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return Result.fail("未登录或登录状态已失效");
        }

        Map<String, Object> preferences = buildDefaultPreferences();
        String preferencesJson = userService.getPreferences(currentUserId);

        if (preferencesJson != null && !preferencesJson.trim().isEmpty()) {
            try {
                Map<String, Object> savedPreferences = objectMapper.readValue(
                        preferencesJson,
                        new TypeReference<Map<String, Object>>() {}
                );
                preferences = normalizePreferences(savedPreferences);
            } catch (Exception e) {
                preferences = buildDefaultPreferences();
            }
        }

        return Result.success(preferences);
    }

    @PutMapping("/preferences")
    public Result<Map<String, Object>> updatePreferences(@RequestBody(required = false) Map<String, Object> body) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return Result.fail("未登录或登录状态已失效");
        }

        Map<String, Object> preferences = normalizePreferences(body);

        try {
            String preferencesJson = objectMapper.writeValueAsString(preferences);
            boolean success = userService.updatePreferences(currentUserId, preferencesJson);
            if (!success) {
                return Result.fail("偏好设置保存失败");
            }
        } catch (Exception e) {
            return Result.fail("偏好设置保存失败");
        }

        return Result.success("偏好设置保存成功", preferences);
    }

    @GetMapping("/rank/accepted")
    public Result<List<Map<String, Object>>> getAcceptedRankList(
            @RequestParam(defaultValue = "10") Integer limit) {
        int size = Math.max(1, Math.min(limit == null ? 10 : limit, 50));
        List<User> users = userService.lambdaQuery()
                .select(User::getId,
                        User::getUsername,
                        User::getNickname,
                        User::getAvatar,
                        User::getAcceptedCount,
                        User::getRating)
                .orderByDesc(User::getAcceptedCount)
                .orderByDesc(User::getRating)
                .orderByAsc(User::getId)
                .last("LIMIT " + size)
                .list();
        return Result.success(users.stream().map(u -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", u.getId()); m.put("username", u.getUsername()); m.put("nickname", u.getNickname());
            m.put("avatar", u.getAvatar()); m.put("acceptedCount", u.getAcceptedCount()); m.put("rating", u.getRating());
            return m;
        }).toList());
    }

    @GetMapping("/rank/rating")
    public Result<List<Map<String, Object>>> getRatingRankList(
            @RequestParam(defaultValue = "10") Integer limit) {
        int size = Math.max(1, Math.min(limit == null ? 10 : limit, 50));
        List<User> users = userService.lambdaQuery()
                .select(User::getId,
                        User::getUsername,
                        User::getNickname,
                        User::getAvatar,
                        User::getAcceptedCount,
                        User::getRating)
                .orderByDesc(User::getRating)
                .orderByAsc(User::getId)
                .last("LIMIT " + size)
                .list();
        return Result.success(users.stream().map(u -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", u.getId()); m.put("username", u.getUsername()); m.put("nickname", u.getNickname());
            m.put("avatar", u.getAvatar()); m.put("acceptedCount", u.getAcceptedCount()); m.put("rating", u.getRating());
            return m;
        }).toList());
    }

    @GetMapping("/activity/heatmap/{id}")
    public Result<Map<String, Object>> getUserActivityHeatmap(@PathVariable Long id) {
        if (!canViewProfile(id)) {
            return Result.error(403, "该用户未公开主页，登录后可查看");
        }
        User user = userService.getById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(364);
        LocalDateTime startTime = startDate.atStartOfDay();
        LocalDateTime endTime = endDate.plusDays(1).atStartOfDay();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        List<Map<String, Object>> rawStats = submissionService.getUserDailySubmissionStats(id, startTime, endTime);
        Map<String, Integer> countMap = new HashMap<>();
        long total = 0L;

        for (Map<String, Object> item : rawStats) {
            String submitDate = String.valueOf(item.get("submitDate"));
            int submitCount = Integer.parseInt(String.valueOf(item.get("submitCount")));
            countMap.put(submitDate, submitCount);
            total += submitCount;
        }

        List<Map<String, Object>> days = new ArrayList<>(365);
        for (int i = 0; i < 365; i++) {
            LocalDate date = startDate.plusDays(i);
            Map<String, Object> dayItem = new HashMap<>();
            dayItem.put("date", date.format(formatter));
            dayItem.put("count", countMap.getOrDefault(date.format(formatter), 0));
            days.add(dayItem);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("days", days);
        result.put("startDate", startDate.format(formatter));
        result.put("endDate", endDate.format(formatter));

        return Result.success(result);
    }

    @RateLimit(key = "radar", time = 30, count = 10)
    @GetMapping("/radar/{userId}")
    public Result<List<Map<String, Object>>> radar(@PathVariable Long userId) {
        if (!canViewProfile(userId)) {
            return Result.error(403, "该用户未公开主页，登录后可查看");
        }
        // 1. 获取所有知识域（六维）
        List<Tag> dimensions = tagRatingService.selectDimensions();
        List<Long> dimensionIds = dimensions.stream().map(Tag::getId).toList();

        // 2. 批量查该用户这些维度的子标签
        List<Tag> allChildren = tagRatingService.selectChildrenByDimensions(dimensionIds);
        List<Long> allTagIds = allChildren.stream().map(Tag::getId).toList();
        if (allTagIds.isEmpty()) {
            // 无子标签：直接返回 0 分六维
            return Result.success(dimensions.stream()
                    .map(d -> Map.<String, Object>of("dimension", d.getCode(), "label", d.getName(), "rating", 0))
                    .toList());
        }

        // 3. 查该用户的评分记录
        List<UserTagRating> ratings = tagRatingService.selectRatings(userId, allTagIds);
        Map<Long, Integer> ratingMap = ratings.stream()
                .collect(java.util.stream.Collectors.toMap(UserTagRating::getTagId, r ->
                        r.getRating() != null ? r.getRating() : 800));
        Map<Long, Integer> countMap = ratings.stream()
                .collect(java.util.stream.Collectors.toMap(UserTagRating::getTagId, r ->
                        r.getProblemCount() != null ? r.getProblemCount() : 0));

        // 4. 按维度聚合：加权均值
        List<Map<String, Object>> radarData = new ArrayList<>();
        for (Tag dim : dimensions) {
            List<Tag> children = allChildren.stream()
                    .filter(t -> t.getParentId() != null && t.getParentId().equals(dim.getId()))
                    .toList();
            if (children.isEmpty()) {
                radarData.add(Map.of("dimension", dim.getCode(), "label",
                        TagRatingService.DIMENSION_LABELS.getOrDefault(dim.getCode(), dim.getName()), "rating", 0));
                continue;
            }
            long totalWeight = 0;
            long weightedSum = 0;
            for (Tag child : children) {
                int r = ratingMap.getOrDefault(child.getId(), 800);
                int cnt = countMap.getOrDefault(child.getId(), 0);
                weightedSum += (long) r * cnt;
                totalWeight += cnt;
            }
            int dimRating = totalWeight > 0 ? (int) (weightedSum / totalWeight) : 800;
            radarData.add(Map.of("dimension", dim.getCode(), "label",
                    TagRatingService.DIMENSION_LABELS.getOrDefault(dim.getCode(), dim.getName()), "rating", dimRating));
        }
        return Result.success(radarData);
    }

    @RateLimit(key = "recentAc", time = 30, count = 10)
    @GetMapping("/recent-ac/{userId}")
    public Result<List<Map<String, Object>>> recentAC(@PathVariable Long userId) {
        if (!canViewProfile(userId)) {
            return Result.error(403, "该用户未公开主页，登录后可查看");
        }
        var subs = submissionApplicationService.findRecentAccepted(userId, 5);
        var problemIds = subs.stream().map(top.everblue.oj.judge.entity.Submission::getProblemId).distinct().toList();
        var titles = problemIds.isEmpty() ? java.util.Map.<Long, String>of()
                : problemQuery.findTitleBatch(problemIds);
        var list = subs.stream().map(s -> java.util.Map.<String, Object>of(
                "problemId", s.getProblemId(),
                "title", titles.getOrDefault(s.getProblemId(), "未知题目"),
                "language", s.getLanguage() != null ? s.getLanguage() : "",
                "runTime", s.getRunTime() != null ? s.getRunTime() : 0,
                "createTime", s.getCreateTime() != null ? s.getCreateTime().toString() : ""
        )).toList();
        return Result.success(list);
    }

    @RateLimit(key = "recommend", time = 60, count = 10)
    @GetMapping("/recommend/{userId}")
    public Result<List<Map<String, Object>>> recommend(@PathVariable Long userId) {
        if (!canViewProfile(userId)) {
            return Result.error(403, "该用户未公开主页，登录后可查看");
        }
        // 1. 找 rating 最低的 3 个标签
        var all = tagRatingService.selectRatingsAll(userId);
        if (all.isEmpty()) {
            // 无评分记录：推荐简单题
            var problems = problemQuery.findDigestBatch(java.util.List.of(1L, 2L, 4L, 6L));
            return Result.success(problems.stream().map(p -> java.util.Map.<String, Object>of(
                    "tagName", "入门推荐", "problemId", p.id(), "title", p.title(),
                    "difficulty", p.difficulty())).toList());
        }
        all.sort(java.util.Comparator.comparingInt(r -> r.getRating() != null ? r.getRating() : 800));
        var weakTags = all.subList(0, Math.min(3, all.size()));
        var weakTagIds = weakTags.stream().map(UserTagRating::getTagId).toList();

        // 2. 查这些标签的 tag name
        var tagNames = tagRatingService.selectTagNames(weakTagIds);

        // 3. 找这些标签的题目
        var relations = tagRatingService.findProblemTagsByTagIds(weakTagIds);
        var candidateIds = relations.stream().map(top.everblue.oj.analytics.entity.ProblemTag::getProblemId).distinct().toList();
        if (candidateIds.isEmpty()) {
            return Result.success(java.util.List.of());
        }

        // 4. 排除已 AC
        var solved = profileQuery.findSolvedProblemIds(userId);
        var freshIds = candidateIds.stream().filter(id -> !solved.contains(id)).toList();
        if (freshIds.isEmpty()) {
            return Result.success(java.util.List.of());
        }

        // 5. 查题目摘要，按难度升序取前 5
        var tagByProblem = relations.stream()
                .collect(java.util.stream.Collectors.toMap(
                        top.everblue.oj.analytics.entity.ProblemTag::getProblemId,
                        top.everblue.oj.analytics.entity.ProblemTag::getTagId, (a, b) -> a));
        var result = problemQuery.findDigestBatch(freshIds).stream()
                .filter(d -> Boolean.TRUE.equals(d.isPublic()))
                .sorted(java.util.Comparator.comparingInt(d -> d.difficulty() == null ? 0 : d.difficulty()))
                .limit(4)
                .map(d -> java.util.Map.<String, Object>of(
                        "tagName", tagNames.getOrDefault(tagByProblem.get(d.id()), "综合"),
                        "problemId", d.id(),
                        "title", d.title(),
                        "difficulty", d.difficulty()))
                .toList();
        return Result.success(result);
    }

    @Operation(summary = "个性化学习计划")
    @GetMapping("/learning-plan/{userId}")
    public Result<List<Map<String, Object>>> learningPlan(@PathVariable Long userId) {
        if (!canViewProfile(userId)) {
            return Result.error(403, "该用户未公开主页，登录后可查看");
        }
        User user = userService.getById(userId);
        if (user == null) return Result.error("用户不存在");

        List<Map<String, Object>> plan = new ArrayList<>();
        List<UserTagRating> ratings = tagRatingService.selectRatingsAll(userId);
        ratings.sort(Comparator.comparingInt(item -> item.getRating() == null ? 800 : item.getRating()));
        List<Long> weakTagIds = ratings.stream().limit(2).map(UserTagRating::getTagId).toList();
        Map<Long, String> tagNames = tagRatingService.selectTagNames(weakTagIds);
        Set<Long> solved = new HashSet<>(profileQuery.findSolvedProblemIds(userId));
        int targetRating = user.getRating() == null ? 1000 : user.getRating();

        if (!weakTagIds.isEmpty()) {
            var relations = tagRatingService.findProblemTagsByTagIds(weakTagIds);
            Map<Long, Long> tagByProblem = relations.stream().collect(java.util.stream.Collectors.toMap(
                    top.everblue.oj.analytics.entity.ProblemTag::getProblemId,
                    top.everblue.oj.analytics.entity.ProblemTag::getTagId, (left, right) -> left));
            List<Long> candidates = relations.stream().map(top.everblue.oj.analytics.entity.ProblemTag::getProblemId)
                    .filter(id -> !solved.contains(id)).distinct().toList();
            problemQuery.findDigestBatch(candidates).stream().filter(item -> Boolean.TRUE.equals(item.isPublic()))
                    .sorted(Comparator.comparingInt(item -> Math.abs((item.difficulty() == null ? 800 : item.difficulty()) - targetRating)))
                    .limit(3).forEach(item -> plan.add(planItem("problem", item.id(), item.title(),
                            "优先补齐「" + tagNames.getOrDefault(tagByProblem.get(item.id()), "薄弱知识点") + "」，难度贴近当前 Rating",
                            item.difficulty())));

            for (Long tagId : weakTagIds) {
                if (plan.size() >= 5) break;
                String tag = tagNames.get(tagId);
                if (tag == null) continue;
                List<Map<String, Object>> articles = jdbcTemplate.queryForList(
                        "SELECT id, title, summary FROM post WHERE type = 2 AND privacy = 0 AND is_deleted = 0 "
                                + "AND FIND_IN_SET(?, tags) > 0 "
                                + "ORDER BY is_essence DESC, likes DESC, create_time DESC LIMIT 1", tag);
                if (!articles.isEmpty()) {
                    Map<String, Object> article = articles.get(0);
                    plan.add(planItem("article", ((Number) article.get("id")).longValue(), String.valueOf(article.get("title")),
                            "先阅读「" + tag + "」相关讲解，再完成推荐练习", null));
                }
            }
        }

        if (plan.isEmpty()) {
            List<Map<String, Object>> beginners = jdbcTemplate.queryForList(
                    "SELECT id, title, difficulty FROM problem WHERE (is_deleted = 0 OR is_deleted IS NULL) "
                            + "ORDER BY difficulty ASC LIMIT 3");
            beginners.forEach(item -> plan.add(planItem("problem", ((Number) item.get("id")).longValue(),
                    String.valueOf(item.get("title")), "先建立稳定的提交节奏，从基础题开始", (Integer) item.get("difficulty"))));
        }
        return Result.success(plan.stream().limit(5).toList());
    }

    @Operation(summary = "我的错题本与今日复习")
    @GetMapping("/review-book")
    public Result<List<Map<String, Object>>> reviewBook(@RequestParam(defaultValue = "today") String scope) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.error(401, "请先登录");
        collectReviewProblems(userId);
        String limit = "today".equals(scope) ? " LIMIT 5" : " LIMIT 50";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT review.problem_id AS problemId, problem.title, problem.difficulty, review.reason, "
                        + "review.status, review.last_reviewed_at AS lastReviewedAt, review.first_flagged_at AS firstFlaggedAt "
                        + "FROM user_problem_review review JOIN problem ON problem.id = review.problem_id "
                        + "WHERE review.user_id = ? AND review.status = 'REVIEW' "
                        + "ORDER BY COALESCE(review.last_reviewed_at, review.first_flagged_at) ASC" + limit, userId);
        return Result.success(rows);
    }

    @Operation(summary = "标记错题掌握状态")
    @PostMapping("/review-book/{problemId}/mastered")
    public Result<Void> markReviewMastered(@PathVariable Long problemId, @RequestParam boolean mastered) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.error(401, "请先登录");
        int updated = jdbcTemplate.update("UPDATE user_problem_review SET status = ?, last_reviewed_at = NOW() WHERE user_id = ? AND problem_id = ?",
                mastered ? "MASTERED" : "REVIEW", userId, problemId);
        if (updated == 0) return Result.error("错题记录不存在");
        return Result.success(null);
    }

    private Map<String, Object> planItem(String kind, Long targetId, String title, String reason, Integer difficulty) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", kind); item.put("targetId", targetId); item.put("title", title);
        item.put("reason", reason); item.put("difficulty", difficulty); return item;
    }

    /** 反复失败，或 7 天前尝试后仍未通过的题自动进入复习池；已手动掌握的题不被覆盖。 */
    private void collectReviewProblems(Long userId) {
        jdbcTemplate.update("UPDATE user_problem_review review JOIN ("
                        + "SELECT DISTINCT problem_id FROM submission WHERE user_id = ? AND status = 1"
                        + ") accepted ON accepted.problem_id = review.problem_id "
                        + "SET review.status = 'MASTERED', review.last_reviewed_at = NOW() "
                        + "WHERE review.user_id = ? AND review.status = 'REVIEW'", userId, userId);
        jdbcTemplate.update("INSERT INTO user_problem_review (user_id, problem_id, status, reason, first_flagged_at) "
                        + "SELECT user_id, problem_id, 'REVIEW', "
                        + "CASE WHEN SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END) >= 2 THEN 'REPEATED_WA' ELSE 'UNSOLVED_7_DAYS' END, NOW() "
                        + "FROM submission WHERE user_id = ? GROUP BY user_id, problem_id "
                        + "HAVING SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END) = 0 "
                        + "AND SUM(CASE WHEN status <> 0 THEN 1 ELSE 0 END) > 0 "
                        + "AND (SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END) >= 2 "
                        + "OR MIN(CASE WHEN status <> 0 THEN create_time END) <= DATE_SUB(NOW(), INTERVAL 7 DAY)) "
                        + "ON DUPLICATE KEY UPDATE reason = VALUES(reason), updated_at = NOW()", userId);
    }

}
