package top.everblue.oj.api.ai;

/** AI 助教能力契约，业务入口不直接依赖具体模型供应商。 */
public interface AiAssistant {

    /**
     * 通用对话入口：自定义 system + user prompt，返回纯文本。
     * <p>
     * 用于非答疑场景（如家长报告的薄弱点建议生成）。
     * 实现方应保证失败时返回降级文案而非抛异常。
     */
    String chat(String systemPrompt, String userPrompt);

    /**
     * 结构化对话入口：自定义 system + user prompt，要求模型只输出一个合法 JSON 对象。
     * <p>
     * 实现方应通过 OpenAI 兼容的 {@code response_format: {"type": "json_object"}} 约束输出，
     * 并返回原始 JSON 文本。调用失败（HTTP 非 2xx / 网络异常 / 响应结构异常）时返回 {@code null}，
     * 由调用方决定如何提示用户，不返回降级文案（避免把失败伪装成 JSON 内容）。
     */
    String chatJson(String systemPrompt, String userPrompt);

    /**
     * 教师工具对话：按 toolKey 渲染系统提示词（提示词模板由实现方维护），返回纯文本。
     * <p>
     * 用于非答疑场景（如班级学情 AI 解读）。实现方应保证失败时返回降级文案而非抛异常。
     * </p>
     */
    String chatWithTool(String toolKey, String userPrompt);

    /**
     * 教师工具 JSON 对话：按 toolKey 渲染系统提示词，要求模型只输出一个合法 JSON 对象。
     * <p>
     * 失败（HTTP 非 2xx / 网络异常 / 响应结构异常）时返回 {@code null}，由调用方决定降级。
     * </p>
     */
    String chatJsonWithTool(String toolKey, String userPrompt);
}
