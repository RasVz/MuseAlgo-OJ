package com.musealgo.oj.problem.service.impl;

/** 作答已超时；抛出时保留自动交卷产生的数据库更新。 */
public class AttemptExpiredException extends com.musealgo.oj.common.exception.BusinessException {

    public AttemptExpiredException(String message) {
        super(com.musealgo.oj.problem.error.ProblemErrorCode.EXAM_ATTEMPT_EXPIRED, message, java.util.Map.of("autoSubmitted", true));
    }
}
