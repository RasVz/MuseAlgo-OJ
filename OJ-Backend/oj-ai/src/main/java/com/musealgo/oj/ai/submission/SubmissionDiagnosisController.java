package com.musealgo.oj.ai.submission;

import com.musealgo.oj.ai.dto.AiStreamResult;
import com.musealgo.oj.ai.error.AiErrorCode;
import com.musealgo.oj.ai.gateway.AiStreamControl;
import com.musealgo.oj.common.annotation.RateLimit;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.common.exception.ErrorCode;
import com.musealgo.oj.common.exception.ErrorResponses;
import com.musealgo.oj.common.utils.SecurityUtils;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** 提交详情的一次性诊断：读取走普通接口，生成走同步 SSE，成功后落库。 */
@Slf4j
@RestController
@RequestMapping("/ai/diagnoses")
@RequiredArgsConstructor
@Validated
public class SubmissionDiagnosisController {
    private final SubmissionDiagnosisService diagnoses;
    private final ExecutorService aiStreamExecutor;

    @GetMapping("/{submissionId}")
    public Result<SubmissionDiagnosisView> view(@PathVariable @Positive Long submissionId) {
        return Result.success(diagnoses.view(SecurityUtils.getUserId(), submissionId));
    }

    @PostMapping("/{submissionId}/generate")
    @RateLimit(key = "aiTeaching", action = "AI 诊断", time = 60, count = 6)
    public SseEmitter generate(@PathVariable @Positive Long submissionId) {
        Long user = SecurityUtils.getUserId();
        boolean privileged = SecurityUtils.isTeacherOrAdmin();
        SubmissionDiagnosisService.Prepared prepared = diagnoses.prepare(user, submissionId);
        String execution = diagnoses.claim(prepared, privileged);
        SseEmitter emitter = new SseEmitter(180_000L);
        AiStreamControl control = new AiStreamControl(
                chunk -> sendChunk(emitter, chunk),
                (name, data) -> sendEvent(emitter, name, data));
        emitter.onTimeout(control::cancel);
        emitter.onError(error -> control.cancel());
        emitter.onCompletion(control::cancel);
        long start = System.currentTimeMillis();
        try {
            dispatch(() -> run(prepared, execution, privileged, emitter, control, start));
        } catch (BusinessException busy) {
            diagnoses.fail(prepared, execution, busy.getErrorCode().key(), busy.getMessage(), elapsed(start));
            throw busy;
        }
        return emitter;
    }

    private void run(SubmissionDiagnosisService.Prepared prepared, String execution, boolean privileged,
                     SseEmitter emitter, AiStreamControl control, long start) {
        AiStreamResult result = null;
        try {
            // 领取与注册取消回调之间可能刚被取消；复查后不发送模型请求。
            if (control.isCancelled()) {
                diagnoses.fail(prepared, execution, "AI_DIAGNOSIS_INTERRUPTED", "生成已中断，请重试", elapsed(start));
                return;
            }
            result = diagnoses.stream(prepared, control);
            if (control.isCancelled()) {
                diagnoses.fail(prepared, execution, "AI_DIAGNOSIS_INTERRUPTED", "生成已中断，请重试", elapsed(start));
                return;
            }
            boolean persisted = diagnoses.finish(prepared, execution, result, elapsed(start), privileged);
            if (!persisted) {
                if (!control.isCancelled()) sendError(emitter, AiErrorCode.AI_UPSTREAM_FAILED, "诊断保存失败，请重试");
                return;
            }
            if (result != null && result.success()) sendDone(emitter);
            else sendFailure(emitter, result);
        } catch (AiStreamControl.Cancelled cancelled) {
            diagnoses.fail(prepared, execution, "AI_DIAGNOSIS_INTERRUPTED", "生成已中断，请重试", elapsed(start));
        } catch (BusinessException business) {
            diagnoses.fail(prepared, execution, business.getErrorCode().key(), business.getMessage(), elapsed(start));
            if (!control.isCancelled()) sendError(emitter, business.getErrorCode(), business.getMessage());
        } catch (Exception failure) {
            log.error("提交诊断生成失败 submissionId={}", prepared.data().id(), failure);
            diagnoses.fail(prepared, execution, "AI_UPSTREAM_FAILED", "AI 生成未完成，请重试", elapsed(start));
            if (!control.isCancelled()) sendError(emitter, AiErrorCode.AI_UPSTREAM_FAILED, null);
        } finally {
            safeComplete(emitter);
        }
    }

    private void dispatch(Runnable work) {
        String trace = MDC.get("traceId");
        try {
            aiStreamExecutor.execute(() -> {
                String previous = MDC.get("traceId");
                if (trace == null) MDC.remove("traceId");
                else MDC.put("traceId", trace);
                try {
                    work.run();
                } finally {
                    if (previous == null) MDC.remove("traceId");
                    else MDC.put("traceId", previous);
                }
            });
        } catch (RejectedExecutionException busy) {
            throw new BusinessException(AiErrorCode.AI_STREAM_BUSY);
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private static void sendChunk(SseEmitter emitter, String chunk) {
        try {
            // 第一个空格是 SSE 分隔符，保留增量本身的缩进。
            emitter.send(SseEmitter.event().data(" " + chunk));
        } catch (IOException | IllegalStateException disconnected) {
            throw new AiStreamControl.Cancelled();
        }
    }

    private static void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException disconnected) {
            throw new AiStreamControl.Cancelled();
        }
    }

    private static void sendFailure(SseEmitter emitter, AiStreamResult result) {
        sendError(emitter, errorOf(result), result == null ? null : result.errorMessage());
    }

    private static ErrorCode errorOf(AiStreamResult result) {
        if (result == null) return AiErrorCode.AI_UPSTREAM_FAILED;
        return switch (result.errorType() == null ? "" : result.errorType()) {
            case "CANCELLED" -> AiErrorCode.AI_DIAGNOSIS_INTERRUPTED;
            case "QUOTA_REJECTED" -> AiErrorCode.AI_QUOTA_EXCEEDED;
            case "CONTEST_BLOCKED" -> AiErrorCode.AI_CONTEST_BLOCKED;
            case "TIMEOUT" -> CommonErrorCode.UPSTREAM_TIMEOUT;
            case "OUTPUT_LIMIT" -> AiErrorCode.AI_OUTPUT_LIMIT;
            default -> AiErrorCode.AI_UPSTREAM_FAILED;
        };
    }

    private static void sendDone(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("done").data(""));
        } catch (IOException | IllegalStateException disconnected) {
            log.debug("诊断完成事件未送达");
        }
    }

    private static void sendError(SseEmitter emitter, ErrorCode error, String notice) {
        try {
            emitter.send(SseEmitter.event().name("app-error").data(
                    notice == null ? ErrorResponses.failure(error) : ErrorResponses.failure(error, notice),
                    MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException disconnected) {
            log.debug("诊断错误事件未送达");
        }
    }

    private static void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
        }
    }
}
