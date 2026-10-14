package top.everblue.oj.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 助教问答请求。
 * <p>
 * 替代旧实现里直接接收 {@code Map<String,String>}，让参数有类型与校验，
 * 也避免把超长内容无声地透传给大模型。
 */
@Data
public class AiAskRequest {

    @NotNull(message = "题目 ID 不能为空")
    private Long problemId;

    @NotBlank(message = "题目标题不能为空")
    @Size(max = 200, message = "题目标题过长")
    private String problemTitle;

    /** 题目描述（Markdown），可为空；超出阈值时由 Service 截断，避免 token 爆炸 */
    @Size(max = 16000, message = "题目内容过长")
    private String problemContent;

    /** 学生当前代码，可为空 */
    @Size(max = 16000, message = "代码过长")
    private String studentCode;

    @NotBlank(message = "你还没有提出问题哦！")
    @Size(max = 2000, message = "提问内容过长")
    private String studentQuestion;

    /** Hint Ladder 引导级别 1-4（不传或 0 = 默认引导模式） */
    @Min(value = 0, message = "引导级别范围为 0-4")
    @Max(value = 4, message = "引导级别范围为 0-4")
    private Integer hintLevel;
}
