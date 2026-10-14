package top.everblue.oj.community.post;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.community.entity.Post;
import top.everblue.oj.community.service.PostService;

/**
 * 动态「存在性 + 可操作 + 类型匹配」三类前置校验的唯一实现。
 * <p>
 * 阅读、评论、互动、编辑四条链路都经由本类过一遍，而不是各自
 * {@code getById} 后判一次。原因是这三条规则互为前缀且极易漏判：
 * 软删除过滤、作者归属、{@code commentType} 与 {@code type} 的对应关系，
 * 只要有一个入口绕过（例如后台治理脚本直接用 Mapper 查），
 * 就会出现「能对已删除动态点赞」「能给动态评论却挂了题解类型」这类越权或脏数据。
 * 收在一处后，新增入口只需调用本类，不必重新默写规则。
 * </p>
 * <p>
 * 三个方法都用 {@code require} 前缀命名：返回非空对象或抛异常，不返回 {@code null}，
 * 调用方无需再做判空分支。
 * </p>
 * <p>
 * 实现刻意在包级可见（无 {@code public}），只经 Spring 注入使用；
 * 该接口是模块内共享规则，不需要对外暴露类型。
 * </p>
 */
@Service
@RequiredArgsConstructor
class PostTargetServiceImpl implements PostTargetService {
    private final PostService posts;

    /**
     * 校验动态存在且未被软删除。
     * <p>
     * <b>软删除判定用 {@code Integer.valueOf(1).equals(...)} 而非直接 {@code == 1}：</b>
     * {@code is_deleted} 是 {@link Integer}，MyBatis-Plus 读出的未设置字段为 {@code null}，
     * 自动拆箱会在判空阶段抛 NPE，掩盖真正的「不存在」。用常量在前的方法
     * 让 {@code null} 安全地落到「不是已删除」分支上。
     * <p>
     * 同理，{@code postId} 为 {@code null} 时 {@code getById} 返回 {@code null}，
     * 落入同一条「不存在」分支，不需要单独判空参数。
     * </p>
     *
     * @param postId 动态 ID，可为 {@code null}
     * @return 未删除的动态对象
     * @throws IllegalArgumentException 动态不存在或已删除
     */
    @Override
    public Post requireExisting(Long postId) {
        Post post = posts.getById(postId);
        if (post == null || Integer.valueOf(1).equals(post.getIsDeleted())) throw new IllegalArgumentException("文章不存在");
        return post;
    }

    /**
     * 校验动态存在、可删可见，且操作者是作者本人。
     * <p>
     * 用于编辑与删除动态。<b>作者判定不交给 SQL 层的 {@code WHERE author_id = ?}</b>：
     * 那样的话越权与不存在会返回同一个「影响 0 行」，前端只能报「操作失败」，
     * 无法区分。而且编辑需要先把对象读出来做后续校验（可见性、类型），
     * 先查后判的写法可省掉一次往返。
     * </p>
     *
     * @param actorId 当前登录用户 ID，未登录时为 {@code null}，同样按越权拒绝
     * @return 未删除的动态对象
     * @throws IllegalArgumentException 动态不存在、已删除，或 {@code actorId} 不是作者
     */
    @Override
    public Post requireEditable(Long postId, Long actorId) {
        Post post = requireExisting(postId);
        if (actorId == null || !actorId.equals(post.getAuthorId())) throw new IllegalArgumentException("无权修改他人的动态");
        return post;
    }

    /**
     * 校验动态可作为评论对象，且评论类型与动态类型匹配。
     * <p>
     * <b>业务规则（易被误改，务必留意）：</b>
     * 评论的 {@code entityType} 与动态的 {@code type} 必须落在不同类别：
     * {@code commentType=1} 表示「评论动态」，只能发在非题解（{@code type != 1}）的动态上；
     * {@code commentType=3} 表示「评论题解」，只能发在题解（{@code type == 1}）上。
     * 两者错配会让评论挂到错误的分区，列表页按类型筛选时直接消失——
     * 这类脏数据没有定时任务能修，只能人工清理。
     * </p>
     * <p>
     * {@code commentType} 为 {@code null} 或取 2 等其他值时不拦截，交给上层处理；
     * 本方法只负责这两个已知的对应关系。
     * </p>
     *
     * @param commentType 评论类型：1-动态评论，3-题解评论
     * @return 未删除的动态对象
     * @throws IllegalArgumentException 动态不存在、已删除，或评论类型与动态类型不匹配
     */
    @Override
    public Post requireCommentTarget(Long postId, Integer commentType) {
        Post post = requireExisting(postId);
        boolean solution = Integer.valueOf(1).equals(post.getType());
        if ((Integer.valueOf(3).equals(commentType) && !solution) || (Integer.valueOf(1).equals(commentType) && solution)) {
            throw new IllegalArgumentException("评论对象类型与内容类型不匹配");
        }
        return post;
    }
}
