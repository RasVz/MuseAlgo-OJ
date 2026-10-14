package top.everblue.oj.api.submission;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Outbox 发布的 submission.judged 事件契约。
 * <p>
 * 消费者反序列化此 record 替代原始 Map 解析，
 * 确保消息格式在编译期校验。
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubmissionJudgedEvent(
        Long submissionId,
        Long taskId,
        String judgedAt
) {
}
