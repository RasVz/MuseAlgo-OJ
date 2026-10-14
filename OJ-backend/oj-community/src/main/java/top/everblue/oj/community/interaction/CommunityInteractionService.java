package top.everblue.oj.community.interaction;

/**
 * Single application boundary for community interactions.
 *
 * HTTP adapters must use explicit target states so retries are safe. Legacy
 * toggle endpoints may query state and delegate here, but do not own counters,
 * notifications, or hot-ranking refreshes themselves.
 */
public interface CommunityInteractionService {
    InteractionResult activate(InteractionCommand command);

    InteractionResult deactivate(InteractionCommand command);

    boolean isActive(InteractionCommand command);
}
