package com.musealgo.oj.ai.controller;

import cn.hutool.json.JSONObject;
import com.musealgo.oj.ai.*;
import com.musealgo.oj.ai.dto.*;
import com.musealgo.oj.ai.entity.AiUsage;
import com.musealgo.oj.ai.error.AiErrorCode;
import com.musealgo.oj.ai.gateway.AiStreamControl;
import com.musealgo.oj.ai.quota.AiQuotaService;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import com.musealgo.oj.api.problem.ProblemTutoringQuery;
import com.musealgo.oj.common.annotation.RateLimit;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.exception.*;
import com.musealgo.oj.common.utils.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/** 开放式教学 SSE 与当前用户的题目会话。身份和题面权限均在请求线程解析。 */
@Slf4j
@RestController
@RequestMapping("/ai")
@Validated
@RequiredArgsConstructor
public class AiController {
    private final AiService aiService;
    private final AiQuotaService quotaService;
    private final AiUsageRecorder usageRecorder;
    private final AiChatSessionService chatSessionService;
    private final ExecutorService aiStreamExecutor;
    private final ProblemTutoringQuery problems;

    @GetMapping("/conversation")
    public Result<List<JSONObject>> conversation(@RequestParam @Positive Long problemId) {
        Long user = user(); problems.load(problemId);
        return Result.success(chatSessionService.conversation(user, problemId));
    }
    @DeleteMapping("/conversation")
    public Result<Void> clearConversation(@RequestParam @Positive Long problemId) {
        Long user = user(); problems.load(problemId);
        chatSessionService.clearConversation(user, problemId);
        return Result.success(null);
    }
    @Operation(summary = "开放式 AI 教学对话（流式）")
    @PostMapping("/ask/stream")
    @RateLimit(key = "aiTeaching", action = "AI 提问", time = 60, count = 12)
    public SseEmitter askAiStream(@Valid @RequestBody AiAskRequest request) {
        Long user = user();
        boolean privileged = SecurityUtils.isTeacherOrAdmin();
        var problem = request.getContestId() == null ? problems.load(request.getProblemId()) : null;
        return stream(user, request.getProblemId(), privileged, "ASK",
                control -> aiService.streamChatGuarded(user, request, problem, privileged, control));
    }
    @Operation(summary = "普通练习提交复盘（含 AC，流式）")
    @PostMapping("/review")
    @RateLimit(key = "aiTeaching", action = "AI 提问", time = 60, count = 12)
    public SseEmitter reviewSubmission(@Valid @RequestBody AiReviewRequest request) {
        Long user = user();
        boolean privileged = SecurityUtils.isTeacherOrAdmin();
        var review = aiService.prepareReview(user, request);
        return stream(user, review.data().problemId(), privileged, "REVIEW",
                control -> aiService.reviewSubmissionStream(user, request, review, privileged, control));
    }
    private static Long user() {
        Long user = SecurityUtils.getUserId();
        if (user == null) throw new BusinessException(CommonErrorCode.AUTH_REQUIRED, "请先登录后再使用 AI 助教");
        return user;
    }
    private SseEmitter stream(Long user, Long problem, boolean privileged, String scene,
                              Function<AiStreamControl, AiAskOutcome> work) {
        SseEmitter emitter = new SseEmitter(300_000L);
        AiStreamControl control = new AiStreamControl(chunk -> {
            try {
                // 第一个空格是 SSE 分隔符，保留增量本身的缩进。
                emitter.send(SseEmitter.event().data(" " + chunk));
            } catch (IOException | IllegalStateException disconnected) { throw new AiStreamControl.Cancelled(); }
        });
        emitter.onTimeout(control::cancel);
        emitter.onError(error -> control.cancel());
        emitter.onCompletion(control::cancel);
        dispatch(() -> {
            long start = System.currentTimeMillis();
            AiAskOutcome outcome = null;
            try {
                if (control.isCancelled()) {
                    outcome = new AiAskOutcome(AiUsage.STATUS_CANCELLED, "", null, "", null);
                    return;
                }
                outcome = work.apply(control);
                if (!control.isCancelled() && !AiUsage.STATUS_CANCELLED.equals(outcome.status())) finish(emitter, outcome);
            } catch (AiStreamControl.Cancelled cancelled) {
                outcome = new AiAskOutcome(AiUsage.STATUS_CANCELLED, "", null, "", null);
            } catch (BusinessException business) {
                if (!control.isCancelled()) fail(emitter, business.getErrorCode(), business.getMessage());
            } catch (Exception failure) {
                log.error("AI 教学流式请求失败", failure);
                if (!control.isCancelled()) fail(emitter, AiErrorCode.AI_STREAM_FAILED, null);
            } finally {
                recordUsage(user, problem, start, outcome, privileged, scene);
                safeComplete(emitter);
            }
        });
        return emitter;
    }
    private void dispatch(Runnable work) {
        String trace = MDC.get("traceId");
        try {
            aiStreamExecutor.execute(() -> {
                String previous = MDC.get("traceId");
                if (trace == null) MDC.remove("traceId"); else MDC.put("traceId", trace);
                try { work.run(); } finally {
                    if (previous == null) MDC.remove("traceId"); else MDC.put("traceId", previous);
                }
            });
        } catch (RejectedExecutionException busy) { throw new BusinessException(AiErrorCode.AI_STREAM_BUSY); }
    }
    private void finish(SseEmitter emitter, AiAskOutcome outcome) throws IOException {
        if (outcome != null && AiUsage.STATUS_SUCCESS.equals(outcome.status())) {
            emitter.send(SseEmitter.event().name("done").data(""));
            emitter.complete(); return;
        }
        ErrorCode error = outcome != null && "TIMEOUT".equals(outcome.errorType()) ? CommonErrorCode.UPSTREAM_TIMEOUT
                : outcome != null && "CONTENT_FILTER".equals(outcome.errorType()) ? AiErrorCode.AI_RESPONSE_FILTERED
                : outcome != null && "OUTPUT_LIMIT".equals(outcome.errorType()) ? AiErrorCode.AI_OUTPUT_LIMIT
                : outcome != null && AiUsage.STATUS_QUOTA_REJECTED.equals(outcome.status()) ? AiErrorCode.AI_QUOTA_EXCEEDED
                : outcome != null && AiUsage.STATUS_CONTEST_BLOCKED.equals(outcome.status()) ? AiErrorCode.AI_CONTEST_BLOCKED
                : AiErrorCode.AI_UPSTREAM_FAILED;
        fail(emitter, error, outcome != null && outcome.status().endsWith("BLOCKED") ? outcome.answer() : null);
    }
    private void fail(SseEmitter emitter, ErrorCode error, String notice) {
        try {
            emitter.send(SseEmitter.event().name("app-error").data(
                    notice == null ? ErrorResponses.failure(error) : ErrorResponses.failure(error, notice), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException disconnected) { log.debug("AI 错误事件未送达"); }
        finally { safeComplete(emitter); }
    }
    private static void safeComplete(SseEmitter emitter) {
        try { emitter.complete(); } catch (IllegalStateException ignored) { }
    }
    private void recordUsage(Long user, Long problem, long start, AiAskOutcome outcome, boolean privileged, String scene) {
        try {
            long latency = System.currentTimeMillis() - start;
            if (outcome == null) {
                usageRecorder.record(user, problem, scene, 0, 0, true, latency, AiUsage.STATUS_FAIL, "请求在获得上游结果前失败，未获用量");
                return;
            }
            boolean called = outcome.requestSent();
            boolean estimated = outcome.usage() == null;
            int input = !called ? 0 : estimated ? AiService.estimateTokens(outcome.promptText()) : outcome.usage().promptTokens();
            int output = !called ? 0 : estimated ? AiService.estimateTokens(outcome.answer()) : outcome.usage().completionTokens();
            if (AiUsage.STATUS_SUCCESS.equals(outcome.status())) quotaService.consume(user, input + output, privileged);
            String detail = AiUsage.STATUS_CANCELLED.equals(outcome.status()) ? "用户停止生成；估算仅含已发送输入和已接收输出，最终供应商成本未知"
                    : AiUsage.STATUS_FAIL.equals(outcome.status()) ? (called ? "模型回复未完成：" : "未发送上游请求：") + outcome.errorType() : null;
            usageRecorder.record(user, problem, scene, input, output, estimated, latency, outcome.status(), detail, outcome.source());
        } catch (Exception failure) { log.warn("AI 用量记录失败：{}", failure.getClass().getSimpleName()); }
    }
}
