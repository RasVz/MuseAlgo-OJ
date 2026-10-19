package top.everblue.oj.common.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import top.everblue.oj.common.api.Result;

/**
 * 全局异常兜底：把各类异常翻译成统一的 {@link Result}，避免异常栈直接暴露到前端。
 * <p>
 * 所有分支的 HTTP 状态码都保持 200，业务语义放在 body 的 {@code code} 里（400 参数非法、
 * 403 无权限、429 触发限流、500 未处理异常）。这与 {@code Result} 的「只有 code=200 才算成功」
 * 约定配套：前端拦截器一次读 body 即可，不存在「HTTP 成功但业务失败」这种需要双份判断的状态。
 * </p>
 * <p>
 * 之所以要在这里兜底而不是让异常抛到 Servlet 容器：容器默认的 500 页面是 HTML，前端拿到的不是
 * JSON，会导致错误处理逻辑本身抛解析异常；同时框架默认错误体也不会带上业务 code，
 * 前端拿不到可分支判断的信息。
 * </p>
 * <p>
 * {@link Exception} 兜底分支必须放在最后，且只打印到日志、不回传异常细节给客户端——
 * {@code e.getMessage()} 可能含 SQL 片段或文件路径。它返回 code=500，其余已知异常交给各自的精确分支处理。
 * </p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 限流异常处理 —— 返回 HTTP 429 (Too Many Requests)。
     * <p>
     * 前端可根据 code=429 做特殊 UI 处理（如弹窗倒计时），
     * 也可从 message 中解析剩余秒数做更友好的提示。
     * </p>
     */
    @ExceptionHandler(RateLimitException.class)
    public Result<String> handleRateLimitException(RateLimitException e) {
        // 429 是 HTTP 标准中"请求过多"的状态码，语义明确
        return Result.fail(429, e.getMessage());
    }

    /**
     * 请求体校验失败（@Valid + @RequestBody）—— 取第一个字段的提示信息返回。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<String> handleValidationException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "请求参数校验失败";
        return Result.fail(400, message);
    }

    /**
     * 表单/路径参数校验失败（@Validated + @RequestParam/@PathVariable）。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<String> handleConstraintViolationException(ConstraintViolationException e) {
        return Result.fail(400, e.getConstraintViolations().iterator().next().getMessage());
    }

    /**
     * 业务参数非法（如文件类型/大小校验失败）—— 统一返回 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<String> handleIllegalArgumentException(IllegalArgumentException e) {
        return Result.fail(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        log.error("未处理的异常", e);
        return Result.error("系统暂时不可用，请稍后重试");
    }

    @ExceptionHandler(ForbiddenException.class)
    public Result<String> handleForbiddenException(ForbiddenException e) {
        return Result.fail(403, e.getMessage());
    }
}
