package top.everblue.oj.common.api;

import lombok.Data;

/**
 * 全局统一响应体 {@code {code, message, data}}。
 * <p>
 * {@code code} 取业务码，<strong>只有 {@code 200} 表示成功</strong>，HTTP 状态码始终为 200。
 * 前端拦截器就是按「code 是否等于 200」决定成功还是弹错，所以这个约定不能改成「HTTP 2xx 即成功」
 * 或引入别的成功码——那会让所有接口的失败分支走到成功逻辑上。
 * </p>
 * <p>
 * 统一封装而不是各接口返回裸对象，是为了让前端只需在一处处理错误提示、登录态失效跳转，
 * 也让 {@link top.everblue.oj.common.exception.GlobalExceptionHandler} 有唯一可落脚的返回类型。
 * </p>
 * <p>
 * {@code fail} 与 {@code error} 两组工厂方法签名重叠，保留是为了不改存量调用点；语义上
 * {@code fail} 用于业务失败（参数不合法、余额不足），{@code error} 用于系统级错误，二者最终都归到 code。
 * </p>
 */
@Data
public class Result<T> {
    private Integer code; // 200成功, 401未登录, 500错误
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.message = "操作成功";
        r.data = data;
        return r;
    }

    public static <T> Result<T> success(String msg, T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.message = msg;
        r.data = data;
        return r;
    }

    public static <T> Result<T> fail(String msg) {
        Result<T> r = new Result<>();
        r.code = 500;
        r.message = msg;
        return r;
    }

    public static <T> Result<T> fail(Integer code, String msg) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = msg;
        return r;
    }

    public static <T> Result<T> error(Integer code, String msg) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = msg;
        return r;
    }

    public static <T> Result<T> error(String msg) {
        Result<T> r = new Result<>();
        r.code = 500;
        r.message = msg;
        return r;
    }
}
