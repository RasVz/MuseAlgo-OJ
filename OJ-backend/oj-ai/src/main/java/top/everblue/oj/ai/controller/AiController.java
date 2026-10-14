package top.everblue.oj.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.everblue.oj.ai.AiChatSessionService;
import top.everblue.oj.ai.AiService;
import top.everblue.oj.ai.dto.AiAskOutcome;
import top.everblue.oj.ai.dto.AiAskRequest;
import top.everblue.oj.ai.dto.AiReviewRequest;
import top.everblue.oj.ai.quota.AiQuotaService;
import top.everblue.oj.ai.entity.AiUsage;
import top.everblue.oj.ai.usage.AiUsageRecorder;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.common.utils.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 教学助手的对外入口：问答、代码复盘、Hint Ladder 解锁进度查询。
 * <p>
 * <strong>两个流式接口返回的是 {@link SseEmitter} 而不是 {@link Result}</strong>，
 * 因此它们不经过普通 MVC 的响应包装，也不走全局异常处理器——请求阶段的异常
 * （未登录、参数校验失败）在方法返回前抛出，仍由 {@code @RestControllerAdvice} 兜底；
 * 响应开始推流之后的异常只能靠流本身收尾（complete），此时 HTTP 状态已经发出去了，改不了了。
 * 这是 SSE 与普通接口最本质的差别，改这两个接口时务必记得异常处理只能分两段写。
 * </p>
 * <p>
 * 模型调用必须放到受管线程池而不是请求线程：SSE 连接会一直挂着等模型逐字返回，
 * 直接占用 Tomcat 线程会在并发稍高时把整个线程池耗尽，表现为全站接口一起变慢。
 * 另外 <strong>SecurityContext 不会传播到异步线程</strong>，所以用户身份与教师/管理员角色
 * 必须在请求线程内取好再传进去——漏掉这一步的后果是异步线程里取不到用户，
 * 轻则鉴权失效、重则 NPE，且只在压测时偶现。
 * </p>
 * <p>
 * 客户端断连用私有 {@link ClientGoneException} 作控制信号并禁用堆栈：SSE 场景下
 * 「用户关掉页面」是最常见路径而非异常，每秒刷一份堆栈会把日志冲垮；
 * 它也<strong>不进全局异常处理器</strong>，避免被翻译成一个前端会弹错误的 500。
 * </p>
 * <p>
 * 用量记账放在 {@code finally} 里是为了「一条不漏」：成功、模型失败、配额拒绝、比赛拦截都要留痕，
 * 否则配额统计会与实际调用量对不上。计费用量优先取供应商返回的精确值，
 * 供应商未返回时才按字符估算并标记 {@code estimated}。
 * <strong>配额只在成功时扣减</strong>，失败不该扣学生的额度。
 * </p>
 */
@Slf4j
@Tag(name = "AI 助手", description = "AI 教学助手问答与解题指导")
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;
    private final AiQuotaService quotaService;
    private final AiUsageRecorder usageRecorder;
    private final AiChatSessionService chatSessionService;

    /** 流式请求专用线程池（AiExecutorConfig 中的受管 Bean，应用关闭时自动 shutdown） */
    private final ExecutorService aiStreamExecutor;

    /**
     * 查询当前学生对某题已解锁的 Hint Ladder 级别（1~4，未解锁返回 1）。
     * <p>
     * 前端挂载/切换题目时调用，恢复跨设备、跨刷新的解锁状态；
     * 服务端强制级别受该返回值约束（{@link AiService} 内还会二次钳制）。
     * </p>
     */
    @Operation(summary = "查询当前用户 Hint Ladder 已解锁级别")
    @GetMapping("/hint/level")
    public Result<Integer> hintLevel(@RequestParam Long problemId) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new ForbiddenException("请先登录后再使用 AI 教练");
        }
        return Result.success(chatSessionService.getHintLevel(userId, problemId));
    }

    @Operation(summary = "AI 问答（流式）")
    @PostMapping("/ask/stream")
    public SseEmitter askAiStream(@Valid @RequestBody AiAskRequest request) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new ForbiddenException("请先登录后再使用 AI 教练");
        }
        // 角色判定必须在请求线程完成：异步线程池中 SecurityContext 不传播
        boolean privileged = SecurityUtils.isTeacherOrAdmin();

        // 5 分钟上限；模型边生成边 send，首字延迟是聊天体验的关键指标
        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicBoolean clientGone = new AtomicBoolean(false);
        emitter.onTimeout(() -> {
            clientGone.set(true);
            log.warn("【AI流式】SSE 超时（5分钟），终止推送 userId={}, problemId={}", userId, request.getProblemId());
        });
        emitter.onCompletion(() ->
                log.debug("【AI流式】SSE 完成 userId={}, problemId={}", userId, request.getProblemId()));

        aiStreamExecutor.execute(() -> {
            long startMs = System.currentTimeMillis();
            AiAskOutcome outcome = null;
            try {
                outcome = aiService.streamChatGuarded(
                        userId,
                        request.getProblemId(),
                        request.getProblemTitle(),
                        request.getProblemContent(),
                        request.getStudentCode(),
                        request.getStudentQuestion(),
                        request.getHintLevel() == null ? 0 : request.getHintLevel(),
                        privileged,
                        chunk -> {
                            if (clientGone.get()) {
                                // 客户端已断开：尽快中断模型流读取，释放连接与线程
                                throw new ClientGoneException();
                            }
                            try {
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IOException | IllegalStateException e) {
                                // IOException = 网络断开；IllegalStateException = emitter 已超时/完成
                                clientGone.set(true);
                                throw new ClientGoneException();
                            }
                        });

                if (clientGone.get()) {
                    return;
                }
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (ClientGoneException e) {
                log.info("【AI流式】客户端断开，停止推送 userId={}", userId);
                safeComplete(emitter);
            } catch (Exception e) {
                log.warn("AI 流式请求中断或失败: {}", e.getMessage());
                safeComplete(emitter);
            } finally {
                recordUsage(userId, request.getProblemId(), startMs, outcome, privileged, "ASK");
            }
        });
        return emitter;
    }

    @Operation(summary = "AI 代码复盘（流式，WA/TLE/RE/CE 提交）")
    @PostMapping("/review")
    public SseEmitter reviewSubmission(@Valid @RequestBody AiReviewRequest request) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new ForbiddenException("请先登录后再使用 AI 教练");
        }
        boolean privileged = SecurityUtils.isTeacherOrAdmin();

        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicBoolean clientGone = new AtomicBoolean(false);
        emitter.onTimeout(() -> {
            clientGone.set(true);
            log.warn("【AI复盘】SSE 超时（5分钟），终止推送 userId={}, submissionId={}",
                    userId, request.getSubmissionId());
        });
        emitter.onCompletion(() ->
                log.debug("【AI复盘】SSE 完成 userId={}, submissionId={}",
                        userId, request.getSubmissionId()));

        aiStreamExecutor.execute(() -> {
            long startMs = System.currentTimeMillis();
            AiAskOutcome outcome = null;
            try {
                outcome = aiService.reviewSubmissionStream(
                        userId,
                        request.getSubmissionId(),
                        privileged,
                        chunk -> {
                            if (clientGone.get()) {
                                throw new ClientGoneException();
                            }
                            try {
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IOException | IllegalStateException e) {
                                clientGone.set(true);
                                throw new ClientGoneException();
                            }
                        });

                if (clientGone.get()) {
                    return;
                }
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (ClientGoneException e) {
                log.info("【AI复盘】客户端断开，停止推送 userId={}", userId);
                safeComplete(emitter);
            } catch (Exception e) {
                log.warn("AI 复盘流式中断或失败: {}", e.getMessage());
                safeComplete(emitter);
            } finally {
                recordUsage(userId, request.getProblemId(), startMs, outcome, privileged, "REVIEW");
            }
        });
        return emitter;
    }

    /** 客户端断连信号（控制流用，不进入全局异常处理） */
    private static final class ClientGoneException extends RuntimeException {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this; // 纯信号异常，不需要堆栈
        }
    }

    private static void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // emitter 已处于完成/超时状态，重复 complete 会抛 IllegalStateException
        }
    }

    /** 全路径记账：成功 / 失败 / 配额拒绝 / 比赛拦截，一条不漏 */
    private void recordUsage(Long userId, Long problemId, long startMs,
                             AiAskOutcome outcome, boolean privileged, String scene) {
        try {
            long latency = System.currentTimeMillis() - startMs;
            if (outcome == null) {
                usageRecorder.record(userId, problemId, scene, 0, 0, true, latency,
                        AiUsage.STATUS_FAIL, "流式执行异常（未获得结构化结果）");
                return;
            }

            String status = outcome.status();
            int promptTokens = 0;
            int completionTokens = 0;
            boolean estimated = true;

            if (AiUsage.STATUS_SUCCESS.equals(status)) {
                if (outcome.usage() != null) {
                    // API 精确用量
                    promptTokens = outcome.usage().promptTokens();
                    completionTokens = outcome.usage().completionTokens();
                    estimated = false;
                } else {
                    // 供应商未返回 usage → 字符估算
                    promptTokens = AiService.estimateTokens(outcome.promptText());
                    completionTokens = AiService.estimateTokens(outcome.answer());
                }
                // 只有成功调用才消耗配额：失败/拦截不该扣学生的额度
                quotaService.consume(userId, promptTokens + completionTokens, privileged);
            }

            usageRecorder.record(userId, problemId, scene, promptTokens, completionTokens,
                    estimated, latency, status,
                    AiUsage.STATUS_FAIL.equals(status) ? "模型调用失败" : null);
        } catch (Exception e) {
            log.warn("【AI用量】记账异常（不影响主流程）: {}", e.getMessage());
        }
    }
}
