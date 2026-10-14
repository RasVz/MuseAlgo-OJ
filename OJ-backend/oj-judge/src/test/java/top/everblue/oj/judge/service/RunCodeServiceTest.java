package top.everblue.oj.judge.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.everblue.oj.api.judge.JudgeGateway;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * 在线运行（RunCodeService）状态语义回归契约：
 * Judge0 状态码 5=TLE / 6=CE / 7=MLE / 8=OLE，7 必须独立映射为「内存超限」，
 * 不得与 8-13 一并归并为 Runtime Error（历史语义丢失，2026-08-15 修复锁定）。
 */
@ExtendWith(MockitoExtension.class)
class RunCodeServiceTest {

    @Mock
    private JudgeGateway judgeGateway;
    @InjectMocks
    private RunCodeService runCodeService;

    private Map<String, Object> judgeResult(int status) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("stdout", "");
        result.put("stderr", "");
        result.put("compile_output", "");
        result.put("message", "");
        return result;
    }

    @Test
    void mleIsMappedToMemoryLimitExceededNotRuntimeError() {
        when(judgeGateway.judge(any(), any(), any(), any(), anyDouble(), anyInt()))
                .thenReturn(judgeResult(7));

        Map<String, Object> result = runCodeService.executeCode("int main(){}", "cpp", "");

        assertEquals("Memory Limit Exceeded", result.get("statusText"));
        assertTrue(String.valueOf(result.get("stdout")).contains("【内存超限】"));
    }

    @Test
    void tleAndAcceptedSemanticsRemainStable() {
        when(judgeGateway.judge(any(), any(), any(), any(), anyDouble(), anyInt()))
                .thenReturn(judgeResult(5), judgeResult(3));

        Map<String, Object> tle = runCodeService.executeCode("int main(){}", "cpp", "");
        assertEquals("Time Limit Exceeded", tle.get("statusText"));
        assertTrue(String.valueOf(tle.get("stdout")).contains("【运行超时】"));

        Map<String, Object> ac = runCodeService.executeCode("int main(){}", "cpp", "");
        assertEquals("Accepted", ac.get("statusText"));
    }

    @Test
    void runtimeErrorGroupStillCoversJudge0Status8To13() {
        when(judgeGateway.judge(any(), any(), any(), any(), anyDouble(), anyInt()))
                .thenReturn(judgeResult(9));

        Map<String, Object> result = runCodeService.executeCode("int main(){}", "cpp", "");

        assertEquals("Runtime Error", result.get("statusText"));
        assertTrue(String.valueOf(result.get("stdout")).contains("【运行报错】"));
    }
}
