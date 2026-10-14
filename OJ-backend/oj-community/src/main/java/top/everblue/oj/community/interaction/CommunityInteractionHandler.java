package top.everblue.oj.community.interaction;

/** Internal seam for target-specific interaction rules. */
/**
 * 互动处理器的内部契约：一种互动目标对应一个实现。
 * <p>
 * 「点赞 / 取消点赞」在不同目标上的副作用差别很大——动态要点计数、刷热度排行、
 * 发通知并处理唯一键冲突；评论只改 Redis 集合即可。把这些差异收在实现类里，
 * {@link CommunityInteractionService} 因此只需做分派，不必写满 {@code instanceof} 分支。
 * </p>
 * <p>
 * 注意 {@code activate} / {@code deactivate} 是<b>显式目标态</b>而非「翻转」：
 * 重复激活同一互动结果一致，调用方无需先查状态。
 * </p>
 */
interface CommunityInteractionHandler {
    InteractionTargetType targetType();

    InteractionResult activate(InteractionCommand command);

    InteractionResult deactivate(InteractionCommand command);

    boolean isActive(InteractionCommand command);
}
