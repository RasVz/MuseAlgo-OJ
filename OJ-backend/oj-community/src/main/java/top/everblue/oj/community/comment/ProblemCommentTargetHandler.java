package top.everblue.oj.community.comment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.community.entity.Comment;
import top.everblue.oj.api.problem.ProblemQuery;
import top.everblue.oj.community.notification.NotificationTargetType;

import java.util.Set;

/** Owns validation for problem discussions. Problems do not expose comment totals yet. */
/**
 * 题目讨论区的评论目标处理器。
 * <p>
 * 与动态处理器相比只做校验、<b>不做任何计数回写</b>：
 * {@code problem} 表没有冗余的评论数列，题目页若要显示评论数需另行统计。
 * 因此 {@link #afterCommentPublished} 刻意留空——这是「尚未实现」而非「无需实现」，
 * 补上计数前请先确认题目的评论数该存在哪。
 * </p>
 * <p>
 * 通知接收人为 {@code null}：题目不归属个人，没有「作者」可通知，
 * 故题目评论不产生站内通知。
 * </p>
 */
@Service
@RequiredArgsConstructor
class ProblemCommentTargetHandler implements CommentTargetHandler {

    private final ProblemQuery problemQuery;

    @Override
    public Set<Integer> supportedTypes() {
        return Set.of(2);
    }

    @Override
    public CommentTargetDetails requireTarget(Long targetId, Integer targetType) {
        // 题目存在性 + 未删除校验走 oj-api 只读契约
        if (!problemQuery.existsNotDeleted(targetId)) {
            throw new IllegalArgumentException("评论目标不存在");
        }
        return new CommentTargetDetails(NotificationTargetType.PROBLEM, null);
    }

    /**
     * 评论发布后的收尾。
     * <p>
     * 空实现是<b>有意</b>的：题目表没有冗余评论数列，无需回写计数。
     * 该方法存在的意义在于让接口对三类目标保持统一形状，
     * 未来题目接入评论计数时只需在此补实现，调用方无需改动。
     * </p>
     */
    @Override
    public void afterCommentPublished(Comment comment, CommentTargetDetails target) {
        // Problem comments currently have no denormalized count to maintain.
    }
}
