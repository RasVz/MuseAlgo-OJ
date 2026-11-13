package com.musealgo.oj.judge.diagnostics;

import com.musealgo.oj.api.judge.*;
import com.musealgo.oj.api.problem.ProblemDebugReferenceQuery;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.judge.error.JudgeErrorCode;
import com.musealgo.oj.judge.service.RunCodeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CodeExecutionAdapter implements CodeExecutionGateway {
    private final RunCodeService runner;
    private final RunReceiptStore receipts;
    private final ProblemDebugReferenceQuery references;
    private final ObjectProvider<RunUsageSink> telemetry;
    public CodeExecutionAdapter(RunCodeService runner, RunReceiptStore receipts, ProblemDebugReferenceQuery references, ObjectProvider<RunUsageSink> telemetry) {
        this.runner = runner; this.receipts = receipts; this.references = references; this.telemetry = telemetry;
    }
    @Override public Observation run(Request request) {
        validate(request);
        long start = System.nanoTime();
        String origin = "DIAGNOSTIC".equals(request.origin()) ? "run.auto" : "run.manual";
        metric(origin, request.userId(), 1);
        try {
            var raw = runner.executeCode(request.code(), request.language(), request.input());
            int status = ((Number) raw.getOrDefault("status", 0)).intValue();
            String stdout = Objects.toString(raw.get("raw_stdout"), "");
            String stderr = Objects.toString(raw.get("stderr"), "");
            String compile = Objects.toString(raw.get("compile_output"), "");
            String message = Objects.toString(raw.get("message"), "");
            boolean truncated = stdout.length() > 8192 || stderr.length() > 4096 || compile.length() > 4096 || message.length() > 2048;
            var result = new Observation(UUID.randomUUID().toString(), request.input(), clip(stdout,8192), clip(stderr,4096), clip(compile,4096),
                    status, Objects.toString(raw.get("time"), null), memoryKb(raw.get("memory")), truncated, clip(message,2048));
            metric(com.musealgo.oj.judge.domain.SubmissionStatus.isInfrastructureFailure(status) ? "run.service-error" : status == 3 ? "run.completed" : status == 5 ? "run.timeout" : "run.code-error", request.userId(), 1);
            receipts.save(request.userId(), request.problemId(), request.code(), request.language(), result);
            return result;
        } catch (RuntimeException failure) { metric("run.service-error", request.userId(), 1); throw failure; }
        finally { metric("run.elapsed-ms", request.userId(), (System.nanoTime() - start) / 1_000_000); }
    }
    @Override public List<Observation> reuse(Long user, Long problem, String code, String language, List<String> ids) {
        if (ids == null) return List.of();
        var result = ids.stream().distinct().limit(5).map(id -> receipts.read(user, problem, code, language, id)).filter(Objects::nonNull).toList();
        metric("run.reused", user, result.size()); return result;
    }
    @Override public Verification verify(Request request, String suggestedExpected) {
        return verify(request,suggestedExpected,List.of());
    }
    @Override public Verification verify(Request request,String suggestedExpected,List<String> ids) {
        validate(request);
        var cached=reuse(request.userId(),request.problemId(),request.code(),request.language(),ids).stream().filter(value->Objects.equals(value.input(),request.input())).findFirst();
        var observation = cached.orElseGet(()->run(request));
        if (com.musealgo.oj.judge.domain.SubmissionStatus.isInfrastructureFailure(observation.status()))
            return new Verification(observation,null,"NONE",false,"执行服务内部异常，不能判断学生代码");
        if (observation.status() == 5 || (observation.status() >= 7 && observation.status() <= 12))
            return new Verification(observation, null, "RUNTIME_ERROR", true, "该输入使提问时的代码出现运行异常");
        if (observation.status() != 3) return new Verification(observation,null,"NONE",false,"编译/执行未完成，不能确认为输入反例");
        var reference = references.find(request.problemId(), request.input());
        String expected = reference.officialExpected(), source = expected != null ? "OFFICIAL_SAMPLE" : "NONE";
        if (expected == null && reference.verifiedProgram() != null && !reference.verifiedProgram().isBlank()) {
            var oracle = run(new Request(request.userId(), request.problemId(), reference.verifiedProgram(), "cpp", request.input(), "DIAGNOSTIC"));
            if (oracle.status() == 3 && !oracle.truncated()) { expected = oracle.stdout(); source = "REFERENCE_PROGRAM"; }
        }
        if (expected == null && suggestedExpected != null) { expected = suggestedExpected; source = "AI_GUESS"; }
        boolean confirmed = observation.status() == 3 && !observation.truncated() && !"NONE".equals(source) && !"AI_GUESS".equals(source)
                && !observation.stdout().stripTrailing().equals(expected.stripTrailing());
        return new Verification(observation, expected, source, confirmed, confirmed ? "该输入已实测输出不匹配，期望来自" + ("OFFICIAL_SAMPLE".equals(source) ? "官方样例" : "已验证参考程序") : "仅报告运行事实；未确认输出错误");
    }
    private static Integer memoryKb(Object raw) {
        if (raw == null) return null;
        try { return Integer.valueOf(raw.toString()); }
        catch (NumberFormatException unavailable) { return null; }
    }
    private void metric(String name, Long user, long amount) { try { telemetry.ifAvailable(sink -> sink.add(name,user,amount)); } catch(RuntimeException ignored) { /* 资源统计不阻止学生试跑。 */ } }
    private static String clip(String value, int max) { return value.length() <= max ? value : value.substring(0,max); }
    private static void validate(Request request) {
        if (request.userId() == null || request.code() == null || request.code().isBlank()) throw new BusinessException(JudgeErrorCode.SUBMISSION_CODE_REQUIRED);
        if (request.code().length() > 16000 || request.input() == null || request.input().length() > ("DIAGNOSTIC".equals(request.origin()) ? 4096 : 65535))
            throw new BusinessException(JudgeErrorCode.JUDGE_INPUT_INVALID, "代码或样例输入超出试跑范围");
    }
}
