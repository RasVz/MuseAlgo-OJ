package top.everblue.oj.ai.hint;

/**
 * Hint Ladder 分级提示（A1，架构评审功能规划最高优先级）。
 * <p>
 * L1 思路方向 → L2 关键观察 → L3 伪代码 → L4 完整题解，逐级解锁。
 * 级别规则拼进系统提示词，由模型按当前级别控制"给多少"；
 * 解锁约束在 {@link top.everblue.oj.ai.AiChatSessionService}（Redis）服务端强制，
 * 前端 UI 只是第一道闸。
 * </p>
 */
public final class HintLadderGuide {

    private HintLadderGuide() {
    }

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 4;

    /** 返回某个引导级别的提示词规则块；非法级别返回空串（= 默认引导模式） */
    public static String guideFor(int level) {
        return switch (level) {
            case 1 -> """
                    【本轮引导级别：L1 思路方向】
                    - 只告诉学生这道题的宏观思路方向（例如"先想清楚状态是什么，再看状态怎么转移"）。
                    - 不给出具体数据结构名、算法名或任何代码；结尾用 1 个苏格拉底式问题让学生自己迈第一步。""";
            case 2 -> """
                    【本轮引导级别：L2 关键观察】
                    - 在思路方向基础上，指出解题的关键观察（例如"注意交换后什么量不变"），
                      可以点名该用的数据结构/算法（前缀和、单调栈、DP 状态定义等）。
                    - 不写可运行代码；最多给 1~2 行"思路示意"，关键处留空让学生补。""";
            case 3 -> """
                    【本轮引导级别：L3 伪代码】
                    - 给出完整伪代码框架（步骤式），核心逻辑行用自然语言描述。
                    - 可以给 C++ 函数签名与骨架（main 读取、循环结构），但核心转移/判断行必须用
                      ____（填空）代替，并明确提示"这里需要你补全"。
                    - 不给出完整可提交的 AC 代码。""";
            case 4 -> """
                    【本轮引导级别：L4 完整题解】
                    - 完整讲解思路推导，并给出可提交的 C++ 完整 AC 代码，关键行带注释。
                    - 遵守输出格式规范：4 空格缩进、运算符两侧空格、```cpp 围栏；正文 ≤300 字（代码块不计）。""";
            default -> "";
        };
    }
}
