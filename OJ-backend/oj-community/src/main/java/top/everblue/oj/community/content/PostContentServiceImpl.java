package top.everblue.oj.community.content;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.community.entity.Post;
import top.everblue.oj.community.entity.PostProblemRelation;
import top.everblue.oj.community.entity.UserRelation;
import top.everblue.oj.community.mapper.PostMapper;
import top.everblue.oj.community.mapper.PostProblemRelationMapper;
import top.everblue.oj.community.mapper.UserRelationMapper;
import top.everblue.oj.community.service.CommunityTagService;
import top.everblue.oj.community.service.NotificationService;
import top.everblue.oj.community.service.PostHotService;
import top.everblue.oj.community.service.PostTagCatalog;
import top.everblue.oj.community.notification.NotificationCommand;
import top.everblue.oj.community.notification.NotificationTargetType;
import top.everblue.oj.community.notification.NotificationType;
import top.everblue.oj.community.post.PostTargetService;
import top.everblue.oj.api.problem.ProblemQuery;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 动态的写侧：发布与编辑。
 * <p>
 * 与 {@link top.everblue.oj.community.query.PostQueryService}（读侧）职责对分。
 * 本类只做「作者能改的内容」，凡涉及运营权重或他人内容的操作都不在这里。
 * </p>
 * <p>
 * <b>{@code type} 由服务端推断而非信任前端：</b>
 * {@link #classify} 依据 {@code classId}、{@code problemId}、{@code category}
 * 推导内容类型。前端传 {@code type=2}（知识文章）并不必然成立——
 * 只有真的带了关联题目才算。这是刻意的：文章与题解在评论体系里走的是
 * 不同分支（见 {@link top.everblue.oj.community.post.PostTargetServiceImpl}），
 * 放任前端指定会写出无法归类的动态。
 * </p>
 * <p>
 * 编辑时<b>三个运营标志位强制沿用原值</b>
 * （{@code isOfficial} / {@code isEssence} / {@code isTop}），
 * 它们只由治理流程改动，作者编辑不该影响。
 * </p>
 * <p>
 * 关联题目用「先删后插」的全量覆盖，SQL 次数固定为 2，
 * 换不来 diff 的收益；数量上限 12 道，超出即拒绝而非静默截断。
 * </p>
 */
@Service
@RequiredArgsConstructor
class PostContentServiceImpl implements PostContentService {
    private final PostMapper postMapper;
    private final PostProblemRelationMapper relations;
    private final ProblemQuery problemQuery;
    private final CommunityTagService tags;
    private final UserRelationMapper userRelations;
    private final NotificationService notifications;
    private final PostHotService hotPosts;
    private final PostTargetService targets;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean publish(Post post, Long authorId) {
        post.setAuthorId(authorId);
        post.setIsOfficial(0); post.setIsEssence(0); post.setIsTop(0);
        post.setViews(0); post.setLikes(0); post.setCommentCount(0);
        normalize(post); classify(post);
        if (postMapper.insert(post) != 1) return false;
        replaceRelations(post, true);
        notifyFollowers(post);
        if (isSquareVisible(post)) hotPosts.initNewPost(post.getId(), hotCategory(post), flag(post.getIsTop()), flag(post.getIsEssence()));
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(Post post, Long actorId) {
        if (post == null || post.getId() == null) throw new IllegalArgumentException("参数错误");
        Post previous = targets.requireEditable(post.getId(), actorId);
        // Editorial flags are controlled by the governance flow, never by an author edit.
        post.setIsOfficial(previous.getIsOfficial());
        post.setIsEssence(previous.getIsEssence());
        post.setIsTop(previous.getIsTop());
        normalize(post);
        boolean updated = postMapper.updateById(post) == 1;
        if (!updated) return false;
        Integer effectiveType = post.getType() == null ? previous.getType() : post.getType();
        if (!Objects.equals(effectiveType, 2) && Objects.equals(previous.getType(), 2)) {
            relations.deleteByPostId(post.getId());
        } else if (Objects.equals(effectiveType, 2)) {
            post.setType(effectiveType);
            replaceRelations(post, post.getRelatedProblemIds() != null);
        }
        return updated;
    }

    private void normalize(Post post) { post.setTags(String.join(",", PostTagCatalog.normalizeAndValidate(post.getTags(), tags.enabledNames()))); }

    /**
     * 推断并归一化内容的类型与分类。
     * <p>
     * 判定有优先级，不可调换：班级内容（{@code classId} 非空）压过一切；
     * 其次是题解（绑定了题目，或分类已标为 solution）；
     * 最后才是普通讨论。顺序颠倒会让「班级里发的题解」被归为班级内容，
     * 从题解列表里消失。
     * </p>
     * <p>
     * 最后一支把 {@code type=0} 且分类不在既有问答分类内的内容补成
     * {@code question}——历史数据里有分类为空的动态，
     * 不补的话它们在按分类筛选的页面上永远不出现。
     * </p>
     */
    private void classify(Post post) {
        if (post.getClassId() != null) post.setType(3);
        else if (post.getProblemId() != null || "solution".equals(post.getCategory())) { post.setType(1); post.setCategory("solution"); }
        else if (post.getType() != null && post.getType() == 0 && (post.getCategory() == null || !List.of("question", "experience", "contest-discussion").contains(post.getCategory()))) { post.setType(0); post.setCategory("question"); }
    }

    /**
     * 全量覆盖知识文章的关联题目。
     * <p>
     * 只对 {@code type=2}（知识文章）生效：题解与讨论不关联题目。
     * {@code replace=false} 表示本次编辑未涉及关联题目，此时不删不插，
     * 否则用户只改了标题就会把关联关系清空。
     * </p>
     * <p>
     * 题目存在性校验走 oj-api 的只读契约 {@code findTitleBatch} 一次批量查完，
     * 而不是逐个 {@code findTitle}——后者会把校验变成 N+1。
     * 校验失败直接抛异常回滚，不做静默过滤。
     * </p>
     *
     * @throws IllegalArgumentException 关联题目超过 12 道，或含不存在的题目
     */
    private void replaceRelations(Post post, boolean replace) {
        if (!replace || post.getId() == null || !Objects.equals(post.getType(), 2)) return;
        List<Long> ids = Optional.ofNullable(post.getRelatedProblemIds()).orElse(List.of()).stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > 12) throw new IllegalArgumentException("一篇文章最多关联 12 道练习题");
        // 题目存在性校验走 oj-api 只读契约
        if (!ids.isEmpty() && problemQuery.findTitleBatch(ids).size() != ids.size()) throw new IllegalArgumentException("包含不存在的关联题目");
        relations.deleteByPostId(post.getId());
        ids.forEach(problemId -> { PostProblemRelation relation = new PostProblemRelation(); relation.setPostId(post.getId()); relation.setProblemId(problemId); relations.insert(relation); });
    }

    /**
     * 给作者的粉丝推送新动态通知。
     * <p>
     * 只对广场可见的内容推送：{@code isSquareVisible} 为假时直接返回，
     * 否则会把班级内容或私密动态的存在泄露给所有粉丝。
     * </p>
     * <p>
     * 逐个 fanout 且不设上限——粉丝数极大时这会成为慢请求来源。
     * 当前未引入消息队列，因为通知本身已设计为静默失败、可丢。
     * </p>
     */
    private void notifyFollowers(Post post) {
        if (!isSquareVisible(post)) return;
        String label = Objects.equals(post.getType(), 2) ? "知识文章" : Objects.equals(post.getType(), 1) ? "题解" : "讨论";
        userRelations.selectList(new LambdaQueryWrapper<UserRelation>().eq(UserRelation::getFollowedId, post.getAuthorId())).stream()
                .map(UserRelation::getFollowerId).forEach(userId -> notifications.notify(new NotificationCommand(userId, post.getAuthorId(), NotificationType.FOLLOW_POST,
                        NotificationTargetType.POST, post.getId(), "你关注的用户发布了新的" + label, post.getTitle())));
    }

    private boolean isSquareVisible(Post post) { return post.getClassId() == null && (post.getPrivacy() == null || post.getPrivacy() == 0); }
    private boolean flag(Integer value) { return value != null && value == 1; }
    private String hotCategory(Post post) { return Objects.equals(post.getType(), 1) ? "solution" : post.getCategory(); }
}
