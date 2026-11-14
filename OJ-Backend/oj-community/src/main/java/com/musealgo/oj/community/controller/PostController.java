package com.musealgo.oj.community.controller;

import com.musealgo.oj.common.exception.ErrorResponses;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.community.error.CommunityErrorCode;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.musealgo.oj.api.contest.ContestProblemAccessQuery;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.community.entity.Post;
import com.musealgo.oj.community.entity.vo.PostVO;
import com.musealgo.oj.community.service.PostHotService;
import com.musealgo.oj.community.service.PostService;
import com.musealgo.oj.community.service.CommunityTagService;
import com.musealgo.oj.community.interaction.CommunityInteractionService;
import com.musealgo.oj.community.interaction.InteractionAction;
import com.musealgo.oj.community.interaction.InteractionCommand;
import com.musealgo.oj.community.interaction.InteractionResult;
import com.musealgo.oj.community.interaction.InteractionTargetType;
import com.musealgo.oj.community.content.PostContentService;
import com.musealgo.oj.community.query.PostQueryService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 动态的发布、查询与互动入口。
 * <p>
 * 本类刻意<b>不含任何业务逻辑</b>：写操作委托给
 * {@link com.musealgo.oj.community.service.PostService} /
 * {@link PostContentService} / {@link PostInteractionFacade}，
 * 读操作委托给 {@link PostQueryService}。
 * Controller 里保留的只有取登录态、组装响应、以及把「删掉的动态」等
 * 视图层特例（返回空对象而非报错）翻译成响应。
 * </p>
 * <p>
 * 拆分依据是读写分离而非按实体分：新增一条动态读路径只需动
 * {@code PostQueryService}，不会碰到处方逻辑，两边改动频率与风险都不同。
 * </p>
 */
@Tag(name = "帖子管理", description = "帖子的发布、列表、详情与互动")
@RestController
@RequestMapping("/post")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final PostHotService postHotService;
    private final CommunityTagService communityTagService;
    private final CommunityInteractionService interactionService;
    private final PostContentService postContent;
    private final PostQueryService postQueries;
    private final ContestProblemAccessQuery contestProblems;

    /**
     * 比赛进行期按题目过滤题解/讨论：题目关联是唯一可靠归属条件，未关联的帖子无法判定。
     * 教师/管理员保留查看能力，用于赛后复核与内容管理。
     */
    private boolean blockedByRunningContest(Long problemId) {
        return problemId != null && !SecurityUtils.isTeacherOrAdmin()
                && contestProblems.hasRunningFormalContestProblem(problemId);
    }

    private static <T> IPage<T> emptyPage(Integer currentPage, Integer pageSize) {
        return new Page<>(currentPage == null ? 1 : currentPage, pageSize == null ? 10 : pageSize, 0);
    }

    @Operation(summary = "获取公告列表")
    @GetMapping("/announcements")
    public Result<List<Post>> getAnnouncements() {
        return Result.success(postService.list(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Post>()
                        .eq(Post::getType, 0)
                        .eq(Post::getCategory, "announcement")
                        .eq(Post::getPrivacy, (byte) 0)
                        .orderByDesc(Post::getCreateTime)));
    }

    @Operation(summary = "帖子列表（分页检索）")
    @GetMapping("/list")
    public Result<IPage<PostVO>> getList(
            @RequestParam(defaultValue = "1") Integer currentPage,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer type,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) Long authorId,
            @RequestParam(required = false) Long contestId,
            @RequestParam(required = false) Long problemId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tags,
            @RequestParam(defaultValue = "latest") String sort) {
        if (blockedByRunningContest(problemId)) {
            return Result.success(emptyPage(currentPage, pageSize));
        }
        IPage<PostVO> result = postQueries.list(
                currentPage, pageSize, category, type, parseTypes(types),
                authorId, contestId, problemId, classId, keyword, parseTags(tags), sort);
        hydrateReactionState(result, SecurityUtils.getUserId());
        return Result.success(result);
    }

    @Operation(summary = "题目的相关知识文章")
    @GetMapping("/problem/{problemId:\\d+}/articles")
    public Result<IPage<PostVO>> getRelatedArticles(
            @PathVariable Long problemId,
            @RequestParam(defaultValue = "1") Integer currentPage,
            @RequestParam(defaultValue = "6") Integer pageSize,
            @RequestParam(defaultValue = "latest") String sort) {
        if (blockedByRunningContest(problemId)) {
            return Result.success(emptyPage(currentPage, pageSize));
        }
        IPage<PostVO> result = postQueries.relatedArticles(problemId, currentPage, pageSize, sort);
        hydrateReactionState(result, SecurityUtils.getUserId());
        return Result.success(result);
    }

    @Operation(summary = "热门帖子列表")
    @GetMapping("/hot")
    public Result<IPage<PostVO>> getHotList(
            @RequestParam(defaultValue = "1") Integer currentPage,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer type,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) Long authorId,
            @RequestParam(required = false) Long contestId,
            @RequestParam(required = false) Long problemId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tags) {
        if (blockedByRunningContest(problemId)) {
            return Result.success(emptyPage(currentPage, pageSize));
        }
        return Result.success(postQueries.list(
                currentPage, pageSize, category, type, parseTypes(types),
                authorId, contestId, problemId, classId, keyword, parseTags(tags), "hot"));
    }

    @Operation(summary = "我的帖子列表")
    @GetMapping("/my")
    public Result<IPage<PostVO>> getMyPosts(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(defaultValue = "all") String contentType) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "请先登录");
        return Result.success(postQueries.authoredBy(userId, page, size, contentType));
    }

    @Operation(summary = "文章可选标签")
    @GetMapping("/tag-options")
    public Result<Map<String, List<String>>> getTagOptions() {
        Map<String, List<String>> options = new LinkedHashMap<>(com.musealgo.oj.community.service.PostTagCatalog.options());
        communityTagService.enabledOptions().forEach((group, tags) -> options.merge(group, tags, (left, right) -> {
            List<String> merged = new ArrayList<>(left); merged.addAll(right); return merged;
        }));
        return Result.success(options);
    }

    @Operation(summary = "帖子详情")
    @GetMapping("/{id:\\d+}")
    public Result<Map<String, Object>> getDetail(@PathVariable Long id) {
        Map<String, Object> data;
        data = postQueries.detail(id, SecurityUtils.getUserId());
        if (data != null) {
            Object problemId = data.get("problemId");
            if (problemId instanceof Number number && blockedByRunningContest(number.longValue())) {
                return ErrorResponses.failure(CommonErrorCode.ACCESS_DENIED, "比赛进行中，不提供该题的题解与讨论");
            }
        }
        Long userId = SecurityUtils.getUserId();
        if (data != null && userId != null) {
            data.put("isFavorited", interactionService.isActive(
                    new InteractionCommand(userId, InteractionTargetType.POST, id, InteractionAction.FAVORITE)));
        }
        return data != null ? Result.success(data) : ErrorResponses.failure(CommunityErrorCode.POST_NOT_FOUND, "文章不存在");
    }

    @Operation(summary = "点赞帖子")
    @PostMapping("/{id:\\d+}/like")
    public Result<Map<String, Object>> toggleLike(
            @PathVariable Long id, @RequestParam(defaultValue = "true") Boolean active) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "请先登录");

        InteractionCommand command = new InteractionCommand(userId, InteractionTargetType.POST, id, InteractionAction.LIKE);
        InteractionResult result = Boolean.TRUE.equals(active)
                ? interactionService.activate(command)
                : interactionService.deactivate(command);
        return Result.success(buildStats(id, userId, result));

    }

    @Operation(summary = "踩帖子")
    @PostMapping("/{id:\\d+}/dislike")
    public Result<Map<String, Object>> toggleDislike(
            @PathVariable Long id, @RequestParam(defaultValue = "true") Boolean active) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "请先登录");

        InteractionCommand command = new InteractionCommand(userId, InteractionTargetType.POST, id, InteractionAction.DISLIKE);
        InteractionResult result = Boolean.TRUE.equals(active)
                ? interactionService.activate(command)
                : interactionService.deactivate(command);
        return Result.success(buildStats(id, userId, result));

    }

    @Operation(summary = "发布帖子")
    @PostMapping("/add")
    public Result<String> addPost(@RequestBody Post post) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "请先登录");
        return postContent.publish(post, userId) ? Result.success("发布成功") : ErrorResponses.failure(CommonErrorCode.INTERNAL_ERROR, "发布失败");

    }

    @Operation(summary = "更新帖子")
    @PostMapping("/update")
    public Result<String> updatePost(@RequestBody Post post) {
        if (post.getId() == null) return ErrorResponses.failure(CommunityErrorCode.COMMUNITY_INPUT_INVALID, "参数错误");
        Long userId = SecurityUtils.getUserId();
        return postContent.update(post, userId) ? Result.success("修改成功") : ErrorResponses.failure(CommonErrorCode.INTERNAL_ERROR, "修改失败");

    }

    @Operation(summary = "删除帖子")
    @PostMapping("/delete/{id:\\d+}")
    public Result<String> deletePost(@PathVariable Long id) {
        Post post = postService.getById(id);
        if (post == null) return Result.success("文章已删除");
        Long userId = SecurityUtils.getUserId();
        if (!post.getAuthorId().equals(userId)) return ErrorResponses.failure(CommonErrorCode.ACCESS_DENIED, "无权删除他人的动态");
        return postService.removeById(id) ? Result.success("删除成功") : ErrorResponses.failure(CommonErrorCode.INTERNAL_ERROR, "删除失败");
    }

    // ---- 仅存的轻量 Controller 辅助 ----

    private List<Integer> parseTypes(String types) {
        if (types == null || types.trim().isEmpty()) return null;
        List<Integer> result = new ArrayList<>();
        for (String item : types.split(",")) {
            try { result.add(Integer.parseInt(item.trim())); } catch (NumberFormatException ignored) {}
        }
        return result.isEmpty() ? null : result;
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.trim().isEmpty()) return null;
        List<String> result = new ArrayList<>();
        for (String item : tags.split(",")) {
            String tag = item.trim();
            if (!tag.isEmpty()) result.add(tag);
        }
        return result.isEmpty() ? null : result;
    }

    private Map<String, Object> buildStats(Long postId, Long userId, InteractionResult interactionResult) {
        Map<String, Integer> stats = interactionResult.statistics();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("likeCount", stats.getOrDefault("likes", 0));
        data.put("dislikeCount", stats.getOrDefault("dislikes", 0));
        data.put("favoriteCount", stats.getOrDefault("favorites", 0));
        data.put("commentCount", stats.getOrDefault("comments", 0));
        data.put("views", stats.getOrDefault("views", 0));
        data.put("isLiked", userId != null && postHotService.hasLiked(postId, userId));
        data.put("isDisliked", userId != null && postHotService.hasDisliked(postId, userId));
        return data;
    }

    private void hydrateReactionState(IPage<PostVO> page, Long userId) {
        if (page == null || page.getRecords() == null) return;
        for (PostVO post : page.getRecords()) {
            Map<String, Integer> stats = postHotService.getPostStats(post.getId());
            post.setLikes(stats.getOrDefault("likes", post.getLikes() == null ? 0 : post.getLikes()));
            post.setCommentCount(stats.getOrDefault("comments", post.getCommentCount() == null ? 0 : post.getCommentCount()));
            post.setIsLiked(userId != null && postHotService.hasLiked(post.getId(), userId));
        }
    }
}
