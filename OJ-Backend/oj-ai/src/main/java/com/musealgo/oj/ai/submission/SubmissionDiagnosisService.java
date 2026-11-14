package com.musealgo.oj.ai.submission;

import cn.hutool.json.JSONArray;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.musealgo.oj.ai.AiService;
import com.musealgo.oj.ai.dto.AiStreamResult;
import com.musealgo.oj.ai.entity.AiUsage;
import com.musealgo.oj.ai.error.AiErrorCode;
import com.musealgo.oj.ai.gateway.AiGateway;
import com.musealgo.oj.ai.gateway.AiStreamControl;
import com.musealgo.oj.ai.guard.AiContestGuard;
import com.musealgo.oj.ai.mapper.SubmissionDiagnosisMapper;
import com.musealgo.oj.ai.quota.AiQuotaService;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import com.musealgo.oj.api.problem.ProblemTutoringQuery;
import com.musealgo.oj.api.submission.SubmissionReviewQuery;
import com.musealgo.oj.api.submission.SubmissionReviewQuery.SubmissionReviewData;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/** 一次性提交诊断：请求线程校验并领取，流式生成后原子写入终态。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubmissionDiagnosisService {
    static final String SCENE = "DIAGNOSIS";
    static final int MAX_CONTENT = 1200;
    static final Duration STALE_RUNNING = Duration.ofMinutes(2);

    private final SubmissionDiagnosisMapper mapper;
    private final SubmissionReviewQuery submissions;
    private final ProblemTutoringQuery problems;
    private final SubmissionDiagnosisPrompts prompts;
    private final AiGateway gateway;
    private final AiContestGuard contest;
    private final AiQuotaService quota;
    private final AiUsageRecorder usage;

    public record Prepared(SubmissionReviewData data, ProblemTutoringQuery.Context problem, JSONArray messages) { }

    public SubmissionDiagnosisView view(Long user, Long submissionId) {
        requireUser(user);
        owned(user, submissionId);
        SubmissionDiagnosis row = mapper.selectById(submissionId);
        if (row == null) return null;
        if (!user.equals(row.getUserId())) throw new BusinessException(CommonErrorCode.ACCESS_DENIED);
        if (SubmissionDiagnosis.RUNNING.equals(row.getStatus()) && stale(row.getStartTime())) {
            row.setStatus(SubmissionDiagnosis.FAILED);
            row.setErrorCode("AI_DIAGNOSIS_INTERRUPTED");
            row.setMessage("上次诊断生成中断，请重试");
        }
        return SubmissionDiagnosisView.of(row);
    }

    public Prepared prepare(Long user, Long submissionId) {
        requireUser(user);
        SubmissionReviewData data = owned(user, submissionId);
        if (data.examAttemptId() != null) throw new BusinessException(AiErrorCode.AI_DIAGNOSIS_EXAM_BLOCKED);
        if (data.status() == null || data.status() == 0)
            throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT, "评测尚未完成，请稍后生成诊断");
        if (data.code() == null || data.code().isBlank())
            throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT, "该提交没有可诊断的代码");
        if (contest.shouldBlock(data.contestId(), data.problemId()))
            throw new BusinessException(AiErrorCode.AI_CONTEST_BLOCKED);
        ProblemTutoringQuery.Context problem = problems.load(data.problemId());
        if (problem == null) throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT, "题面暂不可读，请稍后重试");
        return new Prepared(data, problem, prompts.messages(data, problem));
    }

    /** 配额与一次性领取在调用模型前完成；成功后不再允许重新生成。 */
    public String claim(Prepared prepared, boolean privileged) {
        Long user = prepared.data().userId();
        Long submissionId = prepared.data().id();
        LocalDateTime now = LocalDateTime.now();
        SubmissionDiagnosis existing = mapper.selectById(submissionId);
        if (existing != null && SubmissionDiagnosis.SUCCEEDED.equals(existing.getStatus()))
            throw new BusinessException(AiErrorCode.AI_DIAGNOSIS_ALREADY_EXISTS);
        if (existing != null && SubmissionDiagnosis.RUNNING.equals(existing.getStatus())
                && !stale(existing.getStartTime()))
            throw new BusinessException(AiErrorCode.AI_DIAGNOSIS_IN_PROGRESS);
        if (!quota.tryAcquire(user, privileged)) throw new BusinessException(AiErrorCode.AI_QUOTA_EXCEEDED);
        String execution = UUID.randomUUID().toString();
        if (existing == null) {
            SubmissionDiagnosis row = new SubmissionDiagnosis();
            row.setSubmissionId(submissionId);
            row.setUserId(user);
            row.setStatus(SubmissionDiagnosis.RUNNING);
            row.setExecutionId(execution);
            row.setPromptTokens(0);
            row.setCompletionTokens(0);
            row.setTokensEstimated(false);
            row.setLatencyMs(0L);
            row.setStartTime(now);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException collision) {
                throw new BusinessException(AiErrorCode.AI_DIAGNOSIS_IN_PROGRESS);
            }
            return execution;
        }
        // 旧执行令牌同时作为条件：并发重试只有一个能把失败/超时记录重新领取成功。
        int changed = mapper.update(null, new LambdaUpdateWrapper<SubmissionDiagnosis>()
                .eq(SubmissionDiagnosis::getSubmissionId, submissionId)
                .in(SubmissionDiagnosis::getStatus, SubmissionDiagnosis.RUNNING, SubmissionDiagnosis.FAILED)
                .eq(SubmissionDiagnosis::getExecutionId, existing.getExecutionId())
                .set(SubmissionDiagnosis::getStatus, SubmissionDiagnosis.RUNNING)
                .set(SubmissionDiagnosis::getExecutionId, execution)
                .set(SubmissionDiagnosis::getContent, null)
                .set(SubmissionDiagnosis::getErrorCode, null)
                .set(SubmissionDiagnosis::getMessage, null)
                .set(SubmissionDiagnosis::getModel, null)
                .set(SubmissionDiagnosis::getServiceName, null)
                .set(SubmissionDiagnosis::getPromptTokens, 0)
                .set(SubmissionDiagnosis::getCompletionTokens, 0)
                .set(SubmissionDiagnosis::getTokensEstimated, false)
                .set(SubmissionDiagnosis::getLatencyMs, 0L)
                .set(SubmissionDiagnosis::getStartTime, now)
                .set(SubmissionDiagnosis::getFinishTime, null)
                .set(SubmissionDiagnosis::getUpdateTime, now));
        if (changed != 1) throw new BusinessException(AiErrorCode.AI_DIAGNOSIS_IN_PROGRESS);
        return execution;
    }

    public AiStreamResult stream(Prepared prepared, AiStreamControl control) {
        return gateway.stream(prepared.messages(), control);
    }

    public boolean finish(Prepared prepared, String execution, AiStreamResult result, long latency, boolean privileged) {
        boolean sent = result != null && result.requestSent();
        boolean success = result != null && result.success() && result.content() != null && !result.content().isBlank();
        String content = result == null ? null : clipContent(result.content());
        int input = !sent ? 0 : result.usage() == null
                ? AiService.estimateTokens(prepared.messages().toString()) : result.usage().promptTokens();
        int output = !sent ? 0 : result.usage() == null
                ? AiService.estimateTokens(result.content()) : result.usage().completionTokens();
        boolean estimated = result == null || result.usage() == null || result.usageEstimated();
        String status = success ? SubmissionDiagnosis.SUCCEEDED : SubmissionDiagnosis.FAILED;
        var update = new LambdaUpdateWrapper<SubmissionDiagnosis>()
                .eq(SubmissionDiagnosis::getSubmissionId, prepared.data().id())
                .eq(SubmissionDiagnosis::getStatus, SubmissionDiagnosis.RUNNING)
                .eq(SubmissionDiagnosis::getExecutionId, execution)
                .set(SubmissionDiagnosis::getStatus, status)
                .set(SubmissionDiagnosis::getContent, content)
                .set(SubmissionDiagnosis::getErrorCode, success ? null : errorCode(result))
                .set(SubmissionDiagnosis::getMessage, success ? null : clipMessage(errorMessage(result)))
                .set(SubmissionDiagnosis::getPromptTokens, input)
                .set(SubmissionDiagnosis::getCompletionTokens, output)
                .set(SubmissionDiagnosis::getTokensEstimated, estimated)
                .set(SubmissionDiagnosis::getLatencyMs, latency)
                .set(SubmissionDiagnosis::getFinishTime, LocalDateTime.now())
                .set(SubmissionDiagnosis::getUpdateTime, LocalDateTime.now());
        if (result != null && result.source() != null) {
            update.set(SubmissionDiagnosis::getModel, result.source().model())
                    .set(SubmissionDiagnosis::getServiceName, result.source().serviceName());
        }
        boolean persisted = false;
        try {
            persisted = mapper.update(null, update) >= 1;
        } catch (Exception failure) {
            log.error("诊断终态写入失败 submissionId={}", prepared.data().id(), failure);
        } finally {
            if (success) {
                try {
                    quota.consume(prepared.data().userId(), input + output, privileged);
                } catch (Exception failure) {
                    log.warn("诊断配额入账失败 submissionId={}", prepared.data().id());
                }
            }
            try {
                usage.record(prepared.data().userId(), prepared.data().problemId(), SCENE, input, output,
                        estimated, latency, success ? AiUsage.STATUS_SUCCESS : AiUsage.STATUS_FAIL,
                        success ? null : errorCode(result) + ": " + errorMessage(result),
                        result == null ? null : result.source());
            } catch (Exception failure) {
                log.warn("诊断用量流水写入失败 submissionId={}", prepared.data().id());
            }
        }
        return persisted;
    }

    /** 流式异常或客户端断开时收尾；此时没有可用的上游用量，按失败记录流水。 */
    public void fail(Prepared prepared, String execution, String errorCode, String message, long latency) {
        try {
            mapper.update(null, new LambdaUpdateWrapper<SubmissionDiagnosis>()
                    .eq(SubmissionDiagnosis::getSubmissionId, prepared.data().id())
                    .eq(SubmissionDiagnosis::getStatus, SubmissionDiagnosis.RUNNING)
                    .eq(SubmissionDiagnosis::getExecutionId, execution)
                    .set(SubmissionDiagnosis::getStatus, SubmissionDiagnosis.FAILED)
                    .set(SubmissionDiagnosis::getErrorCode, errorCode)
                    .set(SubmissionDiagnosis::getMessage, clipMessage(message))
                    .set(SubmissionDiagnosis::getLatencyMs, latency)
                    .set(SubmissionDiagnosis::getFinishTime, LocalDateTime.now())
                    .set(SubmissionDiagnosis::getUpdateTime, LocalDateTime.now()));
        } catch (Exception failure) {
            log.error("诊断失败状态写入失败 submissionId={}", prepared.data().id(), failure);
        } finally {
            try {
                usage.record(prepared.data().userId(), prepared.data().problemId(), SCENE, 0, 0, true,
                        latency, AiUsage.STATUS_FAIL, errorCode + ": " + message, null);
            } catch (Exception failure) {
                log.warn("诊断失败流水写入失败 submissionId={}", prepared.data().id());
            }
        }
    }

    private SubmissionReviewData owned(Long user, Long submissionId) {
        SubmissionReviewData data = submissions.findById(submissionId).orElseThrow(
                () -> new BusinessException(AiErrorCode.AI_DIAGNOSIS_SUBMISSION_NOT_FOUND));
        if (!user.equals(data.userId())) throw new BusinessException(CommonErrorCode.ACCESS_DENIED);
        return data;
    }

    private static void requireUser(Long user) {
        if (user == null) throw new BusinessException(CommonErrorCode.AUTH_REQUIRED);
    }

    private static boolean stale(LocalDateTime start) {
        return start == null || start.isBefore(LocalDateTime.now().minus(STALE_RUNNING));
    }

    private static String clipContent(String value) {
        if (value == null) return null;
        String text = value.strip();
        return text.length() <= MAX_CONTENT ? text : text.substring(0, MAX_CONTENT) + "\n\n（内容过长，已截断）";
    }

    private static String clipMessage(String value) {
        if (value == null) return null;
        return value.length() <= 512 ? value : value.substring(0, 509) + "...";
    }

    private static String errorCode(AiStreamResult result) {
        if (result == null) return "AI_UPSTREAM_FAILED";
        return switch (result.errorType() == null ? "" : result.errorType()) {
            case "CANCELLED" -> "AI_DIAGNOSIS_INTERRUPTED";
            case "QUOTA_REJECTED" -> "AI_QUOTA_EXCEEDED";
            case "CONTEST_BLOCKED" -> "AI_CONTEST_BLOCKED";
            case "TIMEOUT" -> "UPSTREAM_TIMEOUT";
            case "OUTPUT_LIMIT" -> "AI_OUTPUT_LIMIT";
            default -> "AI_UPSTREAM_FAILED";
        };
    }

    private static String errorMessage(AiStreamResult result) {
        if (result == null) return "AI 服务暂时不可用，请稍后重试";
        return switch (result.errorType() == null ? "" : result.errorType()) {
            case "CANCELLED" -> "生成已中断，请重试";
            case "QUOTA_REJECTED" -> AiErrorCode.AI_QUOTA_EXCEEDED.message();
            case "CONTEST_BLOCKED" -> AiErrorCode.AI_CONTEST_BLOCKED.message();
            case "TIMEOUT" -> "AI 服务响应超时，请稍后重试";
            case "OUTPUT_LIMIT" -> "诊断达到输出上限，请重试";
            default -> result.errorMessage() == null || result.errorMessage().isBlank()
                    ? "AI 生成未完成，请重试" : result.errorMessage();
        };
    }
}
