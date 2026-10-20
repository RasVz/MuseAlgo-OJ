package top.everblue.oj.judge.worker;

/** 任务已被回收/重新领取；旧执行必须停止，不能写结果、完成事件或失败状态。 */
public class StaleJudgeExecutionException extends RuntimeException {
    public StaleJudgeExecutionException() { super("判题执行已过期"); }
}
