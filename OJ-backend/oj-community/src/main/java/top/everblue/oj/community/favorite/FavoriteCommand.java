package top.everblue.oj.community.favorite;

import java.util.Map;

/** A user-directed favorite state change. */
public record FavoriteCommand(Long userId, Long targetId, FavoriteTargetType targetType) {
    public static FavoriteCommand from(Long userId, Map<String, Object> params) {
        if (params == null) throw new IllegalArgumentException("参数错误");
        Object id = params.containsKey("targetId") ? params.get("targetId") : params.get("id");
        Object type = params.containsKey("targetType") ? params.get("targetType") : params.get("type");
        try {
            return new FavoriteCommand(userId, id == null ? null : Long.valueOf(id.toString()), FavoriteTargetType.from(type));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数错误");
        }
    }

    public void validate() {
        if (userId == null || targetId == null || targetType == null) throw new IllegalArgumentException("参数错误");
    }
}
