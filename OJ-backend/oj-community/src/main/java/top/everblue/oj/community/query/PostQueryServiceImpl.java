package top.everblue.oj.community.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.user.UserDisplayInfo;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.community.entity.Post;
import top.everblue.oj.community.entity.UserRelation;
import top.everblue.oj.community.entity.vo.PostVO;
import top.everblue.oj.community.mapper.PostMapper;
import top.everblue.oj.community.mapper.PostProblemRelationMapper;
import top.everblue.oj.community.mapper.UserRelationMapper;
import top.everblue.oj.community.post.PostTargetService;
import top.everblue.oj.community.service.PostHotService;
import top.everblue.oj.community.service.PostService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 动态的读侧查询：列表、相关推荐、作者主页、详情。
 * <p>
 * 与 {@link top.everblue.oj.community.service.PostService}（写侧）职责对分，
 * 这样新增读路径不会碰到动态的写入规则，反之亦然。
 * </p>
 * <p>
 * <b>计数的取值规则：</b>统计数有两个来源——Redis 实时值与 {@code post} 表快照，
 * 二者可能不一致（Redis key 过期、或 Redis 不可用时只读快照）。
 * 本类统一取两者较大值（见 {@link #max}），而不是固定信任某一个：
 * 取小的会在 Redis 数据过期后让计数突然归零，
 * 取大的则最多让数字略偏高，不会出现「明明有人点赞却显示 0」的观感问题。
 * 代价是数字不会随取消互动而下降——这个方向的偏差是可接受的。
 * </p>
 * <p>
 * 可见性判定复用 {@link PostTargetService}，不在此重写软删除与私密规则。
 * </p>
 */
@Service
@RequiredArgsConstructor
class PostQueryServiceImpl implements PostQueryService {
    private final PostMapper mapper;
    private final PostService posts;
    private final PostHotService hotPosts;
    private final UserQuery users;
    private final UserRelationMapper relations;
    private final PostProblemRelationMapper problemRelations;
    private final ObjectMapper objectMapper;
    private final PostTargetService targets;

    @Override
    public IPage<PostVO> list(Integer page, Integer size, String category, Integer type, List<Integer> types,
                              Long authorId, Long contestId, Long problemId, Long classId, String keyword, List<String> tags, String sort) {
        return mapper.selectPostVOList(new Page<>(page, size), category, type, types, authorId, contestId, problemId, classId, keyword, tags, sort);
    }

    @Override
    public IPage<PostVO> relatedArticles(Long problemId, Integer page, Integer size, String sort) {
        return mapper.selectRelatedArticleList(new Page<>(page, size), problemId, sort);
    }

    @Override
    public IPage<PostVO> authoredBy(Long userId, Integer page, Integer size, String contentType) {
        Page<Post> records = new Page<>(page, size);
        LambdaQueryWrapper<Post> query = new LambdaQueryWrapper<Post>().eq(Post::getAuthorId, userId).orderByDesc(Post::getCreateTime);
        applyContentFilter(query, contentType); posts.page(records, query);
        UserDisplayInfo author = users.findDisplayInfo(userId).orElse(null);
        List<PostVO> views = records.getRecords().stream().map(post -> toAuthorView(post, author)).collect(Collectors.toList());
        Page<PostVO> result = new Page<>(page, size, records.getTotal()); result.setRecords(views); return result;
    }

    /**
     * 动态详情。
     * <p>
     * 私密动态对非作者返回 {@code null} 而不是报错：前端据此走「内容不存在」的空态，
     * 与已删除动态的表现一致——若返回 403 等错误，会暴露「这条动态存在但你看不到」，
     * 泄露他人私密内容的存在性。
     * </p>
     * <p>
     * 浏览量在<b>通过可见性校验之后</b>才记：不让不可见内容的查看请求抬高其热度。
     * </p>
     *
     * @return 详情视图字段的扁平 Map（含 {@code relatedProblems} 与实时互动态）；
     *         动态不存在或无权查看时为 {@code null}
     */
    @Override
    public Map<String, Object> detail(Long postId, Long currentUserId) {
        Post raw = targets.requireExisting(postId);
        if (raw.getPrivacy() != null && raw.getPrivacy() == 1 && (currentUserId == null || !currentUserId.equals(raw.getAuthorId()))) return null;
        PostVO view = mapper.selectPostVODetail(postId);
        if (view == null) return null;
        hotPosts.view(postId, currentUserId);
        users.findDisplayInfo(view.getAuthorId()).ifPresent(user -> view.setMotto(user.motto()));
        if (currentUserId != null && view.getAuthorId() != null && !currentUserId.equals(view.getAuthorId())) {
            view.setIsFollowed(relations.selectCount(new LambdaQueryWrapper<UserRelation>().eq(UserRelation::getFollowerId, currentUserId)
                    .eq(UserRelation::getFollowedId, view.getAuthorId())) > 0);
        }
        Map<String, Integer> stats = hotPosts.getPostStats(postId);
        Map<String, Object> result = new LinkedHashMap<>(objectMapper.convertValue(view, Map.class));
        result.put("relatedProblems", problemRelations.selectProblemsByPostId(postId));
        result.put("views", max(stats.get("views"), raw, Post::getViews)); result.put("likeCount", max(stats.get("likes"), raw, Post::getLikes));
        result.put("dislikeCount", max(stats.get("dislikes"), raw, Post::getDislikes)); result.put("favoriteCount", max(stats.get("favorites"), raw, Post::getFavorites));
        result.put("commentCount", max(stats.get("comments"), raw, Post::getCommentCount));
        result.put("isLiked", currentUserId != null && hotPosts.hasLiked(postId, currentUserId));
        result.put("isDisliked", currentUserId != null && hotPosts.hasDisliked(postId, currentUserId));
        return result;
    }

    private PostVO toAuthorView(Post post, UserDisplayInfo author) {
        PostVO view = new PostVO(); BeanUtils.copyProperties(post, view);
        if (author != null) { view.setAuthorName(author.username()); view.setAuthorAvatar(author.avatar()); }
        Map<String, Integer> stats = hotPosts.getPostStats(post.getId());
        view.setViews(stats.getOrDefault("views", value(post.getViews()))); view.setLikes(stats.getOrDefault("likes", value(post.getLikes())));
        view.setCommentCount(stats.getOrDefault("comments", value(post.getCommentCount()))); return view;
    }

    /**
     * 内容分区过滤：把前端的 {@code contentType} 翻译成 type/category 条件。
     * <p>
     * <b>这套映射是历史包袱，别想当然地按 {@code type} 单字段过滤：</b>
     * 同一分区在不同历史时期被写成了不同的 {@code type} 或 {@code category} 取值，
     * 数据里三种都存在。因此每个分区都要 {@code type} 与 {@code category} 的或条件，
     * 且 {@code discussion} 还需显式排除掉已被归入文章/题解的分类
     * （否则 {@code type=0} 的老数据会同时出现在两个分区里）。
     * </p>
     * <p>
     * 取值不匹配任何分区时不加条件，即返回全部——前端传错参数时给出宽结果而非空结果。
     * 若要改成「传错即报错」，需同步确认前端所有调用点。
     * </p>
     */
    private void applyContentFilter(LambdaQueryWrapper<Post> query, String contentType) {
        if ("solution".equals(contentType)) { query.and(filter -> filter.isNotNull(Post::getProblemId).or().eq(Post::getCategory, "solution").or().eq(Post::getType, 1)); return; }
        List<String> articles = List.of("algorithm", "life", "leisure", "contest");
        if ("article".equals(contentType)) { query.and(filter -> filter.eq(Post::getType, 2).or().in(Post::getCategory, articles)); return; }
        if ("discussion".equals(contentType)) query.and(filter -> filter.eq(Post::getType, 3).or(nested -> nested.eq(Post::getType, 0)
                .and(category -> category.isNull(Post::getCategory).or().notIn(Post::getCategory, articles).ne(Post::getCategory, "solution"))));
    }
    private int value(Integer number) { return number == null ? 0 : number; }
    /** 取 Redis 实时值与数据库快照的较大者，理由见类头注释；两个来源缺失时按 0 处理。 */
    private int max(Integer stat, Post post, Function<Post, Integer> getter) { return Math.max(stat == null ? 0 : stat, value(getter.apply(post))); }
}
