package top.everblue.oj.judge.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.judge.JudgeGateway;

import java.util.HashMap;
import java.util.Map;

/**
 * 「运行代码」服务：用单条输入跑一次程序看输出，<strong>不与测试用例比对</strong>，因此不产生提交记录。
 * <p>
 * 与正式提交的本质区别是 {@code expectedOutput} 传 null：这意味着无论结果是对是错都直接展示输出，
 * 学生用它来调试，而正式提交走的是 Judge0 的用例比对，判错就是判错。两者共用同一个判题网关，
 * 但语义完全不同，不要试图用「跑不通就是错」来简化这里。
 * </p>
 * <p>
 * <strong>资源限制是固定的宽松默认值</strong>（3 秒 / 256MB），不用题目的配置：
 * 运行调试的目的是看程序输出了什么，如果按题目限制跑，一个「数组开小了」的调试过程会被 TLE 打断，
 * 反而看不到运行时打印的中间结果。默认值只在这里生效，不影响正式提交。
 * </p>
 * <p>
 * 状态码到展示文案的映射（{@code buildDisplayOutput}）刻意做了兜底串联：
 * Judge0 的错误信息常为空或不完整，逐级回退到下一个字段、最后回退到英文原文，
 * 保证前端输出区永远有内容可显示，而不是一片空白让学生无从判断。
 * </p>
 * <p>
 * 语言归一化对未知语言<strong>抛异常而不是静默回退</strong>：静默回退会让 C++ 代码被当成 Python 送去运行，
 * 学生看到的是一段莫名其妙的语法错误，排查成本远高于直接提示「当前仅支持 C++ / Java / Python」。
 * 注意这里与 {@code JudgeService} 的策略相反——判题网关的默认值优先保证「总能跑出结果」，
 * 而这里面向交互，必须先把问题说清楚。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class RunCodeService {

    private static final Double DEFAULT_TIME_LIMIT_SEC = 3.0;
    private static final Integer DEFAULT_MEMORY_LIMIT_KB = 262144;

    private final JudgeGateway judgeGateway;

    public Map<String, Object> executeCode(String code, String language, String input) {
        String normalizedLanguage = normalizeLanguage(language);
        Map<String, Object> judgeResult = judgeGateway.judge(
                code,
                normalizedLanguage,
                input == null ? "" : input,
                null,
                DEFAULT_TIME_LIMIT_SEC,
                DEFAULT_MEMORY_LIMIT_KB
        );
        return buildRunResult(judgeResult);
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.trim().isEmpty()) {
            return "cpp";
        }

        String normalized = language.trim().toLowerCase();
        switch (normalized) {
            case "cpp":
            case "c++":
            case "c++14":
            case "c++17":
                return "cpp";
            case "java":
            case "java8":
            case "java 8":
                return "java";
            case "python":
            case "python3":
            case "py":
                return "python";
            default:
                throw new IllegalArgumentException("当前仅支持 C++ / Java / Python 运行");
        }
    }

    private Map<String, Object> buildRunResult(Map<String, Object> judgeResult) {
        Map<String, Object> result = new HashMap<>();

        Integer status = parseInteger(judgeResult.get("status"), 0);
        String stdout = getString(judgeResult.get("stdout"));
        String compileOutput = getString(judgeResult.get("compile_output"));
        String stderr = getString(judgeResult.get("stderr"));
        String message = getString(judgeResult.get("message"));

        result.put("status", status);
        result.put("statusText", getStatusText(status));
        result.put("time", judgeResult.get("time"));
        result.put("memory", judgeResult.get("memory"));
        result.put("compile_output", compileOutput);
        result.put("stderr", stderr);
        result.put("message", message);
        result.put("stdout", buildDisplayOutput(status, stdout, compileOutput, stderr, message));

        return result;
    }

    private String buildDisplayOutput(Integer status, String stdout, String compileOutput, String stderr, String message) {
        switch (status) {
            case 3:
                return stdout.isEmpty() ? "(程序无输出)" : stdout;
            case 5:
                return "【运行超时】\n" + firstNonBlank(message, stderr, "Time Limit Exceeded");
            case 6:
                return "【编译失败】\n" + firstNonBlank(compileOutput, message, stderr, "Compilation Error");
            case 7:
                return "【内存超限】\n" + firstNonBlank(message, stderr, "Memory Limit Exceeded");
            case 8:
            case 9:
            case 10:
            case 11:
            case 12:
            case 13:
                return "【运行报错】\n" + firstNonBlank(stderr, message, stdout, "Runtime Error");
            default:
                return firstNonBlank(stdout, compileOutput, stderr, message, "运行失败，请稍后重试");
        }
    }

    private String getStatusText(Integer status) {
        switch (status) {
            case 3:
                return "Accepted";
            case 5:
                return "Time Limit Exceeded";
            case 6:
                return "Compilation Error";
            case 7:
                return "Memory Limit Exceeded";
            case 8:
            case 9:
            case 10:
            case 11:
            case 12:
            case 13:
                return "Runtime Error";
            default:
                return "Unknown";
        }
    }

    private Integer parseInteger(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String getString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }
}
