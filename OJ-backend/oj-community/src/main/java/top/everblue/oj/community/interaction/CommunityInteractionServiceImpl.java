package top.everblue.oj.community.interaction;

import org.springframework.stereotype.Service;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Single entry point for community interactions.
 * <p>
 * Target-specific state, counters, ranking, and notification rules are kept in
 * handlers so this module only owns validation and dispatch.
 * </p>
 * <p>
 * 社区互动的统一入口：按目标类型分派到对应处理器。
 * 本类只负责分派与校验，不持有任何互动状态。
 * 这样做的价值是让所有 HTTP 适配层（不同 Controller、不同响应包装）
 * 走同一套规则，避免「点赞接口有私密校验、收藏接口忘了加」这类不一致。
 * </p>
 * <p>
 * 处理器注册表在构造期建立，重复注册直接启动失败——
 * 与 {@link top.everblue.oj.community.service.impl.CommentServiceImpl} 的
 * 评论类型注册表是同一策略：让路由归属在启动时就确定，而非取决于 Spring 的注入顺序。
 * </p>
 */
@Service
public class CommunityInteractionServiceImpl implements CommunityInteractionService {

    private final Map<InteractionTargetType, CommunityInteractionHandler> handlers;

    public CommunityInteractionServiceImpl(List<CommunityInteractionHandler> handlers) {
        Map<InteractionTargetType, CommunityInteractionHandler> mapped = new EnumMap<>(InteractionTargetType.class);
        for (CommunityInteractionHandler handler : handlers) {
            CommunityInteractionHandler previous = mapped.put(handler.targetType(), handler);
            if (previous != null) {
                throw new IllegalStateException("互动目标只能有一个处理器: " + handler.targetType());
            }
        }
        this.handlers = Map.copyOf(mapped);
    }

    @Override
    public InteractionResult activate(InteractionCommand command) {
        return handlerFor(command).activate(command);
    }

    @Override
    public InteractionResult deactivate(InteractionCommand command) {
        return handlerFor(command).deactivate(command);
    }

    @Override
    public boolean isActive(InteractionCommand command) {
        return handlerFor(command).isActive(command);
    }

    private CommunityInteractionHandler handlerFor(InteractionCommand command) {
        command.validate();
        CommunityInteractionHandler handler = handlers.get(command.targetType());
        if (handler == null) {
            throw new IllegalArgumentException("该内容不支持互动");
        }
        return handler;
    }
}
