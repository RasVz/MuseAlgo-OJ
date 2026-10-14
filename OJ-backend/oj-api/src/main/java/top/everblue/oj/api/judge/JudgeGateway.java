package top.everblue.oj.api.judge;

import java.util.Map;

/**
 * 判题能力的跨模块契约。
 *
 * <p>核心业务只依赖这个接口，不感知 Judge0、HTTP 客户端或 Docker 部署细节。</p>
 */
public interface JudgeGateway {

    /**
     * 提交代码判题（通道 A：判题通道）。
     *
     * @param code            用户提交的源代码
     * @param language        编程语言（如 "cpp"）
     * @param input           标准输入数据
     * @param expectedOutput  期望输出（用于比对，可空）
     * @param timeLimitSec    时间限制（秒）
     * @param memoryLimitKb   内存限制（KB）
     * @return Judge0 原始结果 Map（含 stdout/stderr/time/memory/status 等）
     */
    Map<String, Object> judge(String code,
                               String language,
                               String input,
                               String expectedOutput,
                               Double timeLimitSec,
                               Integer memoryLimitKb);
}
