package top.everblue.oj.community.favorite;

import java.util.Locale;

/** Types persisted by the user_favorite table. */
public enum FavoriteTargetType {
    PROBLEM(1, "problem"),
    PROBLEM_LIST(2, "problemList"),
    CONTEST(3, "contest"),
    POST(4, "post");

    private final int databaseValue;
    private final String responseKey;

    FavoriteTargetType(int databaseValue, String responseKey) {
        this.databaseValue = databaseValue;
        this.responseKey = responseKey;
    }

    public int databaseValue() { return databaseValue; }

    public String responseKey() { return responseKey; }

    public static FavoriteTargetType from(Object raw) {
        if (raw == null) throw new IllegalArgumentException("必须指定收藏对象类型");
        if (raw instanceof Number number) return fromValue(number.intValue());
        String value = raw.toString().trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "problem" -> PROBLEM;
            case "problem_list", "problemlist" -> PROBLEM_LIST;
            case "contest" -> CONTEST;
            case "post" -> POST;
            default -> {
                try { yield fromValue(Integer.parseInt(value)); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("不支持的收藏对象类型"); }
            }
        };
    }

    private static FavoriteTargetType fromValue(int value) {
        for (FavoriteTargetType type : values()) {
            if (type.databaseValue == value) return type;
        }
        throw new IllegalArgumentException("不支持的收藏对象类型");
    }
}
