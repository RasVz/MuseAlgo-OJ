package top.everblue.oj.community.interaction;

import top.everblue.oj.community.entity.vo.PostStatsVO;

/**
 * Small interface for applying a post reaction and reading its client-facing state.
 * <p>
 * 动态互动的对外门面：把「执行动作」与「取回最新快照」绑成一次调用。
 * 存在的原因：若让每个 Controller 分别「调互动 → 再调统计」，
 * 两者之间的写入与读取可能出现偏差（读到的是动作生效前的值），
 * 且各适配层的字段包装会逐渐分叉。
 * 由门面保证「动作 + 快照」成对返回，Controller 就只剩薄薄一层适配。
 * </p>
 */
public interface PostInteractionFacade {
    PostStatsVO apply(Long userId, Long postId, InteractionAction action, boolean active);

    PostStatsVO snapshot(Long userId, Long postId);
}
