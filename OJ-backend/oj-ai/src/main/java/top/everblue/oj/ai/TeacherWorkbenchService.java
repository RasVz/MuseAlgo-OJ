package top.everblue.oj.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.everblue.oj.ai.gateway.AiGateway;
import top.everblue.oj.ai.gateway.PromptRegistry;

/**
 * 教师侧 AI 工作台（命题工作台 B1~B5 + 学情解读）：持有全部教师提示词模板并注册到
 * {@link PromptRegistry}，提供非流式调用入口（失败返回降级文案 / null）。
 * <p>
 * 2026-08-15 大服务类拆分：从 {@link AiService} 拆出（prompt 常量 + 注册 + 教师工具方法），
 * AiService 保留 {@code teacherChat}/{@code teacherWorkbenchJson} 为 @Deprecated 委托（调用方零改动）。
 * </p>
 */
@Slf4j
@Service
public class TeacherWorkbenchService {

    private static final String API_FAIL_FALLBACK = "糟糕，教练的大脑暂时短路了，请稍后再试一下吧！";

    private static final String TEACHER_DRAFT_PROMPT = """
            你是资深信奥命题教练。根据命题要求生成一份完整的题面草稿：
            标题、题目描述、输入输出格式、样例（含样例解释）、数据范围与约束。
            使用 Markdown，中文输出；不写题解，不写评测数据。""";

    private static final String TEACHER_TESTCASE_PROMPT = """
            你是信奥命题教练。根据题面要点生成 5 组测试数据，每组包含输入和期望输出，
            覆盖普通、边界、极端与大数据量场景；数据必须符合题目约束。
            按「用例 N / 输入 / 输出」的格式输出。提示：正式发布前必须人工复核。""";

    private static final String TEACHER_INSIGHT_PROMPT = """
            你是班主任视角的信奥教练。根据班级学情统计生成 150 字左右的中文解读：
            班级整体水平、薄弱环节、两极分化情况与下一步教学建议。只输出解读正文。""";

    /** 班级学情解读（结构化统计 JSON 版，比赛管理右侧面板用） */
    private static final String TEACHER_CLASS_INSIGHT_PROMPT = """
            你是班主任视角的信奥教练，面向五年级零基础的 C++ 入门班。以下 JSON 是系统从
            数据库统计出的班级学情数据，字段说明：
            - className 班级名；days 统计天数；periodStart/periodEnd 统计周期；
              memberCount 班级成员数
            - overview：totalSubmissions 提交总数、acceptedCount AC 数、acRate AC 率（百分数，0~100）、
              activeStudentCount 有提交的学生数、avgSubmissionsPerStudent 人均提交、
              avgDailySubmissions 日均提交
            - daily：每日 submitCount/acCount（按天，无提交的日期为 0）
            - problems：题目统计，已按提交量降序，含 problemTitle/submitCount/acCount/passRate
            - studentTiers：学生分层 active 活跃(≥5 次)/ordinary 一般(1~4 次)/silent 沉寂(0 次)，
              activeRatio 活跃占比
            - topStudents/bottomStudents：提交最多/最少的学生（含 submitCount/acCount）

            请输出一份 300~500 字的中文学情解读，用 Markdown 格式（## 小标题 + 列表），包含：
            1. 班级整体水平：提交量、AC 率、活跃度是否正常，用通俗语言描述
            2. 亮点与进步：活跃学生、提交趋势、AC 表现好的方面
            3. 薄弱环节：点名 passRate 最低的题目（最多 3 题），分析可能的原因
            4. 两极分化：活跃与沉寂学生的比例是否明显，是否需要重点关注
            5. 下一步教学建议：2~3 条具体可执行建议，面向五年级零基础学生，避免生僻术语
            只输出解读正文，不要输出 JSON。""";

    /** 命题工作台（B1）：完整题面生成，结构化 JSON 输出。 */
    private static final String TEACHER_WB_STATEMENT_PROMPT = """
            你是资深信奥命题教练，专为小学生（四~六年级）命制 C++ 算法题。
            根据命题要求生成一份可直接发布的完整题面，必须遵守【平台教学约定】。

            【世界观】默认使用"睿思星球"世界观命名（主人公小慕、机器人伙伴思思、慕老师、
            睿思学院、星海市、知识星等），除非命题要求中明确指定其他风格。

            【题面结构要求】
            1. 题目描述：150~350 字，儿童友好的故事化场景，语言亲切、无暴力或负面暗示；
               必须把计算目标说清楚（输入什么、要算什么、输出什么）。
            2. 输入/输出格式：逐行描述（第一行…第二行…），格式符与题目描述一致，避免歧义。
            3. 样例：2~4 组，覆盖普通与边界情况；每组含 input/output/explanation（样例解释）。
            4. 数据范围：题面正文末尾用「数据范围」小节写清每个输入变量的范围（如 1 ≤ n ≤ 10^5），
               并注明子任务分值比例（如三成数据 n ≤ 1000）。数据规模与难度匹配：
               入门≤10^3、普及≤10^5、提高≤10^6~10^9。
            5. 标签：1~3 个中文知识标签（如 模拟、贪心、二分查找、动态规划、枚举、排序、数学）。

            【平台教学约定】
            - 题面只涉及 + - * / 取模 与比较、逻辑运算可解的算法思路；不依赖位运算技巧（& | ~ ^ << >>）。
            - 数学公式用 LaTeX（$...$）；范围统一用 ≤ 表示。

            【输出要求】只输出一个 JSON 对象（不要 markdown 围栏，不要任何解释文字），结构如下：
            {
              "title": "题目标题（≤20字，抓眼球且点题）",
              "content": "题目描述（Markdown，末尾含「数据范围」小节）",
              "inputDesc": "输入格式（Markdown）",
              "outputDesc": "输出格式（Markdown）",
              "hint": "提示（Markdown，可空字符串）",
              "samples": [{"input": "...", "output": "...", "explanation": "..."}],
              "tags": ["标签1", "标签2"],
              "difficultyScore": 800~3000 的整数分（与难度匹配）
            }
            """;

    /** 命题工作台（B2）：标准答案生成，结构化 JSON 输出。 */
    private static final String TEACHER_WB_STD_PROMPT = """
            你是资深信奥教练。根据题目生成一份可通过全部数据（含大数据）的标准答案（std），
            必须遵守【平台教学约定】。

            【代码要求】
            1. C++17 单文件，可复制直接提交；必须能通过该题全部数据，复杂度最优或足够优。
            2. 平台教学约定：统一使用 using namespace std（不使用 std:: 前缀）；只用 <iostream>
               的 cin/cout，禁用 scanf/printf；解法禁止用位运算技巧（& | ~ ^ << >>），
               用常规算术与逻辑运算实现；4 空格缩进，运算符两侧加空格，变量名语义化。
            3. 面向小学生：代码风格清晰、无晦涩技巧；关键步骤写中文注释，解释"这一步在干什么"。

            【输出要求】只输出一个 JSON 对象（不要 markdown 围栏），结构如下：
            {
              "code": "完整 C++ 代码（字符串）",
              "explanation": "算法思路讲解（150~300 字，中文，含复杂度分析）",
              "complexity": "如 O(n log n) 时间 / O(n) 空间"
            }
            """;

    /** 命题工作台（B3）：测试数据生成，结构化 JSON 输出。 */
    private static final String TEACHER_WB_TESTDATA_PROMPT = """
            你是信奥命题教练。根据题面与约束生成测试数据（含样例与边界），
            数据必须能被标准答案程序正确验证。

            【生成要求】
            1. 默认生成 6 组（用户指定数量时按指定）：第 1 组 = 题面样例；其余覆盖：
               普通随机数据 1~2 组、边界（n=1/最小值/最大值）、极端（n 取上限、数值取上限、
               全部相同、严格递增/递减等）、特殊结构（如全 0、负数边界、大数溢出风险）。
            2. 数据必须严格满足题面约束；输入格式与题面完全一致（每行元素个数、空格分隔）。
            3. 期望输出只含答案本身（不打印多余提示语）；行尾不留空格。
            4. 每组给 note：一句话说明该组测试意图（如"最大数据量，验证时间复杂度"）。
            5. 输出必须精简：每组 input 与 output 各不超过 200 字符；恰好 6 组；
               禁止重复组、禁止超长输入、禁止无意义的重复字符（如连续几百个相同括号或数字）。
            6. 当题面输入是字符串/数组序列时，\"最大长度\"用例的输入控制在 10~30 个字符即可，
               在 note 里注明\"验证最大长度\"即可，不要真的输出几百上千个字符的长串；
               字符串题若需要构造很长的重复串，用较短但有代表性的串（如 20 个左右）代替。

            【输出要求】只输出一个 JSON 对象（不要 markdown 围栏），结构如下：
            {
              "cases": [{"input": "...", "output": "...", "note": "测试意图"}]
            }
            """;

    /** 命题工作台（B5）：测试数据生成器代码生成（Python 生成器 + 分布规划），结构化 JSON 输出。 */
    private static final String TEACHER_WB_GENERATOR_PROMPT = """
            你是信奥命题教练。根据题面与数据范围，编写一个 Python 3 生成器程序：
            运行它会在标准输出（stdout）一次性输出多组测试数据（每组只含输入部分），
            每组数据之间用单独一行 ===CASE=== 分隔。

            【生成器要求】
            1. 单文件 Python 3，只用标准库（random 等）；不得读写文件、不得联网、不得用第三方库。
            2. 从题面与数据范围中提取约束（n 的范围、数值范围、字符串长度、矩阵行列等），
               分组规划测试数据并覆盖边界：默认 30 组 = 随机大小数据约 25 组
               （大小覆盖小/中/大）、边界与极端数据约 5 组（n=1、最小值、最大值、
               全部相同、严格递增/递减、全 0、负数边界、大数溢出风险等，按题面取舍）。
            3. 每组数据必须严格满足题面输入格式（行数、每行元素个数、空格分隔）。
            4. 程序只 print 测试数据，禁止 print 任何解释文字/提示语；每组数据之间
               用单独一行 print("===CASE===") 分隔，组间不要有多余空行。
            5. 每组 input 不超过 4KB，全部组输出合计不超过 120KB；
               数值题用固定种子 random.seed(20260815) 保证可复现。
            6. 若题面是数组/序列题，最大规模用例控制在可打印范围内（如 n ≤ 2000 的整数数组）。

            【输出要求】只输出一个 JSON 对象（不要 markdown 围栏），结构如下：
            {
              "code": "完整的 Python 生成器代码（字符串）",
              "plan": [{"group": "随机小数据", "count": 10, "desc": "n 取 1~10 的随机数"}]
            }
            plan 必须覆盖全部生成组数并说明每组意图；plan 各组 count 之和 = 生成组数。
            """;

    /** 命题工作台（B4）：命名实体抽取 + 睿思星球 IP 改名建议，结构化 JSON 输出。 */
    private static final String TEACHER_WB_RENAME_PROMPT = """
            你是睿思学院的 IP 内容策划。从题目题面中抽取所有"命名实体"（人名、地名、机构、
            物品、宠物、特殊称谓等专有名词），并为每个实体建议一个"睿思星球"风格的新名称，
            用于把普通题目改造成我们的原创 IP 题。

            【抽取要求】
            1. 只抽取命名实体；数字、数学术语（数组、背包、二分）、量词、普通名词不抽取；
               若某个普通名词是世界观关键地点且影响故事（如"学校""商店"），可抽取。
            2. 覆盖全部出现过的实体，不遗漏、不重复（同名实体合并，count=出现次数）。
            3. 新名称建议规则：
               - 优先使用【用户指定映射】（original→ipName 已给出时，必须原样采用）。
               - 其次从【IP 宇宙】中选择或仿照其风格创造（主角小慕、机器人思思、慕老师、
                 睿思学院、星海市、知识星、星环空间站、乱码怪…）。
               - 不同实体避免重名；新名称保持儿童友好、不超过 6 个字。
            4. 若【用户指定映射】包含题面中不存在的名字，忽略即可。

            【输出要求】只输出一个 JSON 对象（不要 markdown 围栏），结构如下：
            {
              "entities": [
                {"original": "题面中的原名", "type": "人物|地点|机构|物品|其他", "count": 3, "ipName": "新名称"}
              ]
            }
            """;

    private final AiGateway aiGateway;
    private final PromptRegistry promptRegistry;

    public TeacherWorkbenchService(AiGateway aiGateway, PromptRegistry promptRegistry) {
        this.aiGateway = aiGateway;
        this.promptRegistry = promptRegistry;
        // 版本化注册：后续改提示词只新增版本号，不影响历史链路
        promptRegistry.register("teacher-draft", 1, TEACHER_DRAFT_PROMPT);
        promptRegistry.register("teacher-testcase", 1, TEACHER_TESTCASE_PROMPT);
        promptRegistry.register("teacher-insight", 1, TEACHER_INSIGHT_PROMPT);
        promptRegistry.register("teacher-class-insight", 1, TEACHER_CLASS_INSIGHT_PROMPT);
        promptRegistry.register("teacher-wb-statement", 1, TEACHER_WB_STATEMENT_PROMPT);
        promptRegistry.register("teacher-wb-std", 1, TEACHER_WB_STD_PROMPT);
        promptRegistry.register("teacher-wb-testdata", 1, TEACHER_WB_TESTDATA_PROMPT);
        promptRegistry.register("teacher-wb-generator", 1, TEACHER_WB_GENERATOR_PROMPT);
        promptRegistry.register("teacher-wb-rename", 1, TEACHER_WB_RENAME_PROMPT);
    }

    /**
     * 教师侧 AI 工具（A4）：命题草稿 / 测试数据 / 学情解读。
     * 非流式，失败返回降级文案；不写对话历史。
     */
    public String teacherChat(String toolKey, String userPrompt) {
        JSONArray messages = new JSONArray();
        messages.add(systemMessage(promptRegistry.render(toolKey)));
        messages.add(userMessage(userPrompt));
        String answer = aiGateway.complete(messages, false);
        return answer != null ? answer : API_FAIL_FALLBACK;
    }

    /**
     * 命题工作台（B）：结构化 JSON 生成（题面 / std / 测试数据 / IP 改名抽取）。
     * 使用 jsonMode 输出，失败返回 null，由调用方决定降级文案；不写对话历史。
     */
    public String teacherWorkbenchJson(String toolKey, String userPrompt) {
        JSONArray messages = new JSONArray();
        messages.add(systemMessage(promptRegistry.render(toolKey)));
        messages.add(userMessage(userPrompt));
        return aiGateway.complete(messages, true);
    }

    private static JSONObject systemMessage(String content) {
        JSONObject msg = new JSONObject();
        msg.set("role", "system");
        msg.set("content", content);
        return msg;
    }

    private static JSONObject userMessage(String content) {
        JSONObject msg = new JSONObject();
        msg.set("role", "user");
        msg.set("content", content);
        return msg;
    }
}
