package com.musealgo.oj.problem.application;

/**
 * 标准答案保存时的用例一致性冲突：验证期间测试用例已被修改，
 * 本次验证结果不能代表当前用例集合，因此拒绝把 std 标记为「已测试」。
 * <p>
 * 单独建一个异常类型而不是复用 {@link IllegalArgumentException}：
 * 调用方（Controller）需要把它翻译成「本次未保存，请重新测试」这种正常业务结果，
 * 而不是通用参数错误。抛出时事务已回滚，不会留下半写状态。
 * </p>
 */
public class CaseSetChangedException extends com.musealgo.oj.common.exception.BusinessException {

    public CaseSetChangedException(String message) {
        super(com.musealgo.oj.problem.error.ProblemErrorCode.PROBLEM_CASE_SET_CHANGED, message);
    }
}
