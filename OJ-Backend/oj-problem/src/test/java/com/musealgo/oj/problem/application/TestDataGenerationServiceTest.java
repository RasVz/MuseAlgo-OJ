package com.musealgo.oj.problem.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.musealgo.oj.api.judge.JudgeGateway;
import com.musealgo.oj.common.security.AuthenticatedUser;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemCase;
import com.musealgo.oj.problem.service.ProblemCaseService;
import com.musealgo.oj.problem.service.ProblemService;
import com.musealgo.oj.problem.service.TestCaseStorageService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 标准答案验证的基本契约：没有用例不能算「全部通过」，判题异常只能计为失败。
 * <p>
 * 权限走真实 SecurityContext（教师身份 + 题目作者本人），不使用静态 mock。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class TestDataGenerationServiceTest {

    private static final Long AUTHOR_ID = 3L;

    @Mock
    private ProblemService problemService;
    @Mock
    private ProblemCaseService problemCaseService;
    @Mock
    private TestCaseStorageService testCaseStorageService;
    @Mock
    private JudgeGateway judgeGateway;

    private TestDataGenerationService service;
    private Problem problem;

    @BeforeEach
    void setUp() {
        service = new TestDataGenerationService(problemService, problemCaseService,
                testCaseStorageService, judgeGateway);
        problem = new Problem();
        problem.setId(9L);
        problem.setAuthorId(AUTHOR_ID);
        problem.setTimeLimit(1000);
        problem.setMemoryLimit(256);

        AuthenticatedUser principal = () -> AUTHOR_ID;
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ProblemCase caseOf(long id, int order) {
        ProblemCase one = new ProblemCase();
        one.setId(id);
        one.setProblemId(9L);
        one.setCaseOrder(order);
        one.setScore(100);
        one.setInputFilePath("9/" + id + "/input.txt");
        one.setOutputFilePath("9/" + id + "/output.txt");
        return one;
    }

    @Test
    void verifyRejectsProblemWithoutCasesInsteadOfPassingVacuously() {
        when(problemService.getById(9L)).thenReturn(problem);
        when(problemCaseService.getProblemCases(9L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.verifyStdAgainstCases(9L, "int main(){}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("暂无测试用例");
    }

    @Test
    void acceptedCaseIsCountedAsPassedAndFingerprintCaptured() {
        when(problemService.getById(9L)).thenReturn(problem);
        when(problemCaseService.getProblemCases(9L)).thenReturn(List.of(caseOf(1L, 1)));
        when(testCaseStorageService.readByRelativePath("9/1/input.txt")).thenReturn("1");
        when(testCaseStorageService.readByRelativePath("9/1/output.txt")).thenReturn("1");
        when(judgeGateway.judge(anyString(), eq("cpp"), eq("1"), eq("1"), anyDouble(), anyInt()))
                .thenReturn(Map.of("status", 3, "stdout", "1"));

        TestDataGenerationService.StdVerifyResult result = service.verifyStdAgainstCases(9L, "int main(){}");

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.passed()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.caseFingerprint()).isNotBlank();
    }

    @Test
    void judgeServiceExceptionCountsAsFailure() {
        when(problemService.getById(9L)).thenReturn(problem);
        when(problemCaseService.getProblemCases(9L)).thenReturn(List.of(caseOf(1L, 1)));
        when(testCaseStorageService.readByRelativePath(anyString())).thenReturn("1");
        when(judgeGateway.judge(anyString(), anyString(), any(), any(), anyDouble(), anyInt()))
                .thenThrow(new IllegalStateException("judge0 unavailable"));

        TestDataGenerationService.StdVerifyResult result = service.verifyStdAgainstCases(9L, "int main(){}");

        assertThat(result.passed()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.reason()).contains("判题服务异常"));
    }

    @Test
    void wrongAnswerFailureIsDescribedInPlainLanguage() {
        // Judge0 状态 4 = Wrong Answer；此前显示为「运行失败（状态 4）」，教师看不出是答案错误
        assertThat(TestDataGenerationService.describeFailure(4, Map.of()))
                .isEqualTo("输出与期望不符（答案错误）");
        assertThat(TestDataGenerationService.describeFailure(5, Map.of())).isEqualTo("运行超时");
        assertThat(TestDataGenerationService.describeFailure(3, Map.of())).isEqualTo("无输出");
    }
}
