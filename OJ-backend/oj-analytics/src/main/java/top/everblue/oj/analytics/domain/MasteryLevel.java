package top.everblue.oj.analytics.domain;

/**
 * 掌握度等级——映射到家长报告中的话术。
 */
public enum MasteryLevel {
    EXCELLENT(85, 100, "优秀"),
    GOOD(70, 84, "良好"),
    ADEQUATE(55, 69, "及格"),
    WEAK(0, 54, "薄弱");

    private final int min;
    private final int max;
    private final String label;

    MasteryLevel(int min, int max, String label) {
        this.min = min;
        this.max = max;
        this.label = label;
    }

    public String getLabel() { return label; }

    public int getMin() { return min; }

    public int getMax() { return max; }

    public static MasteryLevel of(int mastery) {
        for (MasteryLevel level : values()) {
            if (mastery >= level.min && mastery <= level.max) return level;
        }
        return WEAK;
    }
}