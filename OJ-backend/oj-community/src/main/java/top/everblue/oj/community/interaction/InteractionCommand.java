package top.everblue.oj.community.interaction;

/** A state-changing interaction request. State is supplied by activate/deactivate. */
public record InteractionCommand(
        Long actorId,
        InteractionTargetType targetType,
        Long targetId,
        InteractionAction action) {

    public void validate() {
        if (actorId == null || targetType == null || targetId == null || action == null) {
            throw new IllegalArgumentException("互动参数不完整");
        }
    }
}
