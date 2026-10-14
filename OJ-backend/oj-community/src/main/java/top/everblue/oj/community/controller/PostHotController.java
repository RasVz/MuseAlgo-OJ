package top.everblue.oj.community.controller;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.community.entity.Post;
import top.everblue.oj.community.entity.dto.PostQueryDTO;
import top.everblue.oj.community.entity.vo.PostHotVO;
import top.everblue.oj.community.entity.vo.PostStatsVO;
import top.everblue.oj.community.service.PostHotService;
import top.everblue.oj.community.service.PostService;
import top.everblue.oj.community.interaction.InteractionAction;
import top.everblue.oj.community.interaction.PostInteractionFacade;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 热门榜单与实时互动统计的对外接口。
 * <p>
 * 榜单 ID 来自 Redis 有序集合（{@link PostHotService}），动态正文与统计快照来自数据库，
 * Controller 只负责把两者对齐、按可见性过滤并组装 {@link PostHotVO}。
 * </p>
 * <p>
 * <b>注意本类承担了本不属于它的重活：</b>
 * {@link #getHotPosts} 每次请求都会先调 {@link #syncHotRankingSnapshot()}，
 * 扫近 30 天更新的动态（上限 2000 条）逐条回写 Redis 排行。
 * 这是为了应对 Redis 重启或 key 淘汰后热榜为空——没有它，热榜会一直空到下一次用户互动。
 * 代价是每次列表请求带一次最多 2000 行的数据库扫描，榜单页 QPS 上升时是明显的瓶颈。
 * 正确方向是把这个补偿动作挪到定时任务（仓库中已有
 * {@code PostMapper#selectRecentActivePosts} 供定时任务用），
 * 在列表接口上做全量同步属于已知的技术债，改动前请先确认定时任务是否已覆盖。
 * </p>
 */
@RestController
@RequestMapping("/api/post")
@Tag(name = "Post Hot System", description = "热门推荐系统相关接口")
@RequiredArgsConstructor
public class PostHotController {

    private final PostHotService postHotService;

    private final PostService postService;

    private final PostInteractionFacade postInteractions;

    @GetMapping("/hot")
    @Operation(summary = "获取热门榜单")
    public Result<Map<String, Object>> getHotPosts(@Validated PostQueryDTO query) {
        Result<Map<String, Object>> result = new Result<>();

        syncHotRankingSnapshot();

        // 1. 从 Redis 获取热榜 postIds
        List<Long> postIds = postHotService.getHotPosts(query.getCategory(), query.getPage(), query.getSize());

        if (postIds == null || postIds.isEmpty()) {
            Map<String, Object> emptyData = new HashMap<>();
            emptyData.put("list", Collections.emptyList());
            emptyData.put("total", 0L);
            result.setCode(200);
            result.setData(emptyData);
            result.setMessage("ok");
            return result;
        }

        // 2. 批量查询数据库获取帖子详情，避免 N+1
        List<Post> posts = postService.listByIds(postIds);
        Map<Long, Post> postMap = posts.stream().collect(Collectors.toMap(Post::getId, p -> p));

        // 3. 组装 VO，并从 Redis 补充实时统计数据
        List<PostHotVO> voList = postIds.stream().map(id -> {
            Post post = postMap.get(id);
            if (post == null) return null;
            if (!shouldIncludeInSquare(post, query.getCategory())) return null;

            PostHotVO vo = new PostHotVO();
            vo.setPostId(post.getId());
            vo.setTitle(post.getTitle());

            if (post.getSummary() != null && !post.getSummary().trim().isEmpty()) {
                vo.setSummary(post.getSummary());
            } else {
                String content = post.getContent() != null ? post.getContent() : "";
                vo.setSummary(content.length() > 100 ? content.substring(0, 100) : content);
            }

            vo.setAuthorId(post.getAuthorId());
            vo.setCategory(resolveHotCategory(post));

            if (post.getTags() != null && !post.getTags().trim().isEmpty()) {
                vo.setTags(Arrays.asList(post.getTags().split(",")));
            } else {
                vo.setTags(Collections.emptyList());
            }

            vo.setCreateTime(post.getCreateTime());
            Map<String, Integer> statsMap = postHotService.getPostStats(id);
            vo.setLikes(statsMap.getOrDefault("likes", post.getLikes() != null ? post.getLikes() : 0));
            vo.setFavorites(statsMap.getOrDefault("favorites", post.getFavorites() != null ? post.getFavorites() : 0));
            vo.setComments(statsMap.getOrDefault("comments", post.getCommentCount() != null ? post.getCommentCount() : 0));
            vo.setViews(statsMap.getOrDefault("views", post.getViews() != null ? post.getViews() : 0));
            vo.setScore(post.getScore());

            return vo;
        }).filter(Objects::nonNull).collect(Collectors.toList());

        // 4. 返回完整结构
        long total = postHotService.getHotPostTotal(query.getCategory());
        Map<String, Object> data = new HashMap<>();
        data.put("list", voList);
        data.put("total", total);

        result.setCode(200);
        result.setData(data);
        result.setMessage("ok");

        return result;
    }

    /**
     * 榜单兜底同步：把近期有更新的动态重新灌入 Redis 排行。
     * <p>
     * 三道限流缺一不可，删掉任何一道都会在数据量上来后打爆内存或数据库：
     * </p>
     * <ul>
     *   <li>只取近 30 天——老动态的热度早已稳定，重算无意义</li>
     *   <li>{@code LIMIT 2000}——硬上限，防止某天批量更新导致一次拉全表</li>
     *   <li>逐条处理——不批量查询排行所需的字段，避免 N+1</li>
     * </ul>
     * <p>
     * 不满足参赛条件的动态（{@link #shouldParticipateInHot} 判否，主要是
     * 非公开 {@code privacy=1}、或绑定了班级 {@code classId}）在此从排行中移除，
     * 保证这类内容不会因为此前曾公开、而后续被改为私密后，仍继续出现在公共榜单里。
     * </p>
     */
    private void syncHotRankingSnapshot() {
        // 只处理近 30 天有更新的帖子，防止全量加载拖垮内存
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<Post> posts = postService.list(
                new LambdaQueryWrapper<Post>()
                        .ge(Post::getUpdateTime, since)
                        .last("LIMIT 2000"));
        if (posts == null || posts.isEmpty()) return;

        for (Post post : posts) {
            String hotCategory = resolveHotCategory(post);
            if (!shouldParticipateInHot(post)) {
                postHotService.removeFromRanking(post.getId(), hotCategory);
                continue;
            }

            postHotService.refreshRankingForPost(
                    post.getId(),
                    hotCategory,
                    resolveBooleanFlag(post.getIsTop()),
                    resolveBooleanFlag(post.getIsEssence()),
                    post.getCreateTime()
            );
        }
    }

    /**
     * 该动态是否够格进公共热榜。
     * <p>
     * 四道门槛缺一不可，去掉任何一道都会让不该外泄的内容出现在广场页：
     * 非公开（{@code privacy=1}）、绑定班级（{@code classId} 非空，仅本班可见）、
     * {@code type} 超出 0~3 的已知取值范围、或关键字段缺失。
     * <p>
     * 注意这里<b>不</b>判 {@code is_deleted}。软删除的动态仍会被
     * {@code syncHotRankingSnapshot} 重新灌回排行（它只看 {@code update_time}，
     * 而删除操作同样会更新该字段），也不会被移出——全仓唯一的移除调用点就在本类的兜底同步里，
     * 且只在 {@link #shouldParticipateInHot} 判否时触发。
     * 实际后果是：软删除的动态会占据热榜名额，但组装 VO 时因数据库查不到而返回 {@code null} 被过滤，
     * 于是列表会出现「总数比实际条目多」的空位。
     * 若要修复，应在 {@link #shouldParticipateInHot} 中加上软删除判定，
     * 而不是在同步逻辑里另开分支。
     * </p>
     */
    private boolean shouldParticipateInHot(Post post) {
        return post != null
                && post.getId() != null
                && post.getClassId() == null
                && (post.getPrivacy() == null || post.getPrivacy() == 0)
                && post.getType() != null
                && (Objects.equals(post.getType(), 0) || Objects.equals(post.getType(), 1)
                || Objects.equals(post.getType(), 2) || Objects.equals(post.getType(), 3));
    }

    private String resolveHotCategory(Post post) {
        if (post == null) {
            return null;
        }
        if (Objects.equals(post.getType(), 1)) {
            return "solution";
        }
        return post.getCategory();
    }

    private boolean resolveBooleanFlag(Integer value) {
        return value != null && value == 1;
    }

    private boolean shouldIncludeInSquare(Post post, String category) {
        if (!shouldParticipateInHot(post)) {
            return false;
        }

        Integer type = post.getType();

        if (category == null || category.trim().isEmpty() || "all".equals(category)) {
            return Objects.equals(type, 0) || Objects.equals(type, 1)
                    || Objects.equals(type, 2) || Objects.equals(type, 3);
        }

        if ("announcement".equals(category)) {
            return Objects.equals(type, 0) && Objects.equals(post.getCategory(), "announcement");
        }

        if ("solution".equals(category)) {
            return Objects.equals(type, 1);
        }

        return Objects.equals(type, 0) && Objects.equals(post.getCategory(), category);
    }

    // 增加正则限制：postId必须为纯数字
    @GetMapping("/{postId:\\d+}/stats")
    @Operation(summary = "获取帖子实时统计数据")
    public Result<PostStatsVO> getPostStats(@PathVariable Long postId) {
        return Result.success(postInteractions.snapshot(SecurityUtils.getUserId(), postId));
    }

    // 增加正则限制：postId必须为纯数字
    @PostMapping("/{postId:\\d+}/like")
    @Operation(summary = "点赞或取消点赞")
    public Result<PostStatsVO> likePost(@PathVariable Long postId, @RequestBody Map<String, Boolean> body) {
        Result<PostStatsVO> result = new Result<>();
        Long userId = SecurityUtils.getUserId();

        if (userId == null) {
            result.setCode(401);
            result.setMessage("请先登录");
            return result;
        }

        Boolean isLike = body.getOrDefault("isLike", true);

        try {
            return Result.success(postInteractions.apply(userId, postId, InteractionAction.LIKE, isLike));
        } catch (IllegalStateException e) {
            result.setCode(409);
            result.setMessage(e.getMessage());
            return result;
        } catch (IllegalArgumentException e) {
            result.setCode(400);
            result.setMessage("参数错误");
            return result;
        }

    }

    // 增加正则限制：postId必须为纯数字
    @PostMapping("/{postId:\\d+}/favorite")
    @Operation(summary = "收藏或取消收藏")
    public Result<PostStatsVO> favoritePost(@PathVariable Long postId, @RequestBody Map<String, Boolean> body) {
        Result<PostStatsVO> result = new Result<>();
        Long userId = SecurityUtils.getUserId();

        if (userId == null) {
            result.setCode(401);
            result.setMessage("请先登录");
            return result;
        }

        Boolean isFavorite = body.getOrDefault("isFavorite", true);

        try {
            return Result.success(postInteractions.apply(userId, postId, InteractionAction.FAVORITE, isFavorite));
        } catch (IllegalStateException e) {
            result.setCode(409);
            result.setMessage(e.getMessage());
            return result;
        } catch (IllegalArgumentException e) {
            result.setCode(400);
            result.setMessage("参数错误");
            return result;
        }

    }

    // 增加正则限制：postId必须为纯数字
    @PostMapping("/{postId:\\d+}/view")
    @Operation(summary = "帖子浏览上报")
    public Result<Void> viewPost(@PathVariable Long postId) {
        Result<Void> result = new Result<>();
        Long userId = SecurityUtils.getUserId(); // 未登录用户返回 null

        postHotService.view(postId, userId);
        result.setCode(200);
        result.setMessage("ok");

        return result;
    }
}
