package top.everblue.oj.problem.service.impl;

/** 作答已超时；抛出时保留自动交卷产生的数据库更新。 */
public class AttemptExpiredException extends IllegalStateException {

    public AttemptExpiredException(String message) {
        super(message);
    }
}
