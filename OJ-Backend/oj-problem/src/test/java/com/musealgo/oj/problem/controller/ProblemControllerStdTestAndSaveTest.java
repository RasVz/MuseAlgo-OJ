package com.musealgo.oj.problem.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.problem.application.CaseSetChangedException;
import com.musealgo.oj.problem.application.ProblemFormatJobService;
import com.musealgo.oj.problem.application.ProblemManagementApplicationService;
import com.musealgo.oj.problem.application.ProblemSearchService;
import com.musealgo.oj.problem.application.ProblemStatementVersionService;
import com.musealgo.oj.problem.application.TestDataGenerationService;
import com.musealgo.oj.problem.importer.FpsImportService;
import com.musealgo.oj.problem.service.ProblemService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标准答案「测试并保存」的落库边界：只有「有失败=0 且验证期间用例未变」才允许标为已测试。
 * <p>
 * 走 Controller 是因为「是否保存」的判定就写在这里；用例数为零的拒绝在
 * {@link TestDataGenerationService#verifyStdAgainstCases} 内完成，由该服务的测试覆盖。
 * </p>
 */
class ProblemControllerStdTestAndSaveTest {

    private ProblemService problemService;
    private ProblemManagementApplicationService appService;
    private TestDataGenerationService testDataGenerationService;
    private ProblemController controller;

    @BeforeEach
    void setUp() {
        problemService = mock(ProblemService.class);
        appService = mock(ProblemManagementApplicationService.class);
        testDataGenerationService = mock(TestDataGenerationService.class);
        controller = new ProblemController(problemService, appService,
                mock(FpsImportService.class), mock(ProblemSearchService.class), testDataGenerationService,
                mock(ProblemFormatJobService.class), mock(ProblemStatementVersionService.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(Result<?> result) {
        assertThat(result.getCode()).isEqualTo(200);
        return (Map<String, Object>) result.getData();
    }

    @Test
    void failingCaseNeverSavesStdAsTested() {
        when(testDataGenerationService.verifyStdAgainstCases(7L, "int main(){}"))
                .thenReturn(new TestDataGenerationService.StdVerifyResult(
                        2, 1, 1,
                        List.of(new TestDataGenerationService.StdFailure(2, "运行超时")), "fp"));

        Result<Map<String, Object>> result = controller.testAndSaveStd(
                new ProblemController.TestAndSaveStdRequest(7L, "int main(){}", ""));

        Map<String, Object> data = dataOf(result);
        assertThat(data.get("saved")).isEqualTo(false);
        assertThat(data.get("failed")).isEqualTo(1);
        assertThat((String) data.get("message")).contains("未通过");
        verify(appService, never()).saveStdTested(anyLong(), any(), any(), any());
    }

    @Test
    void casesChangedDuringVerificationAreNotSaved() {
        when(testDataGenerationService.verifyStdAgainstCases(7L, "int main(){}"))
                .thenReturn(new TestDataGenerationService.StdVerifyResult(2, 2, 0, List.of(), "before"));
        // 保存时的一致性校验在应用服务内部完成：用例已变则抛 CaseSetChangedException
        org.mockito.Mockito.doThrow(new CaseSetChangedException(
                        "验证期间测试用例已被修改，本次结果不能代表当前用例，未保存，请重新测试"))
                .when(appService).saveStdTested(7L, "int main(){}", "", "before");

        Result<Map<String, Object>> result = controller.testAndSaveStd(
                new ProblemController.TestAndSaveStdRequest(7L, "int main(){}", ""));

        Map<String, Object> data = dataOf(result);
        assertThat(data.get("saved")).isEqualTo(false);
        assertThat((String) data.get("message")).contains("已被修改");
    }

    @Test
    void allCasesPassingSavesStdAsTestedWithVerificationFingerprint() {
        when(testDataGenerationService.verifyStdAgainstCases(7L, "int main(){}"))
                .thenReturn(new TestDataGenerationService.StdVerifyResult(3, 3, 0, List.of(), "same"));

        Result<Map<String, Object>> result = controller.testAndSaveStd(
                new ProblemController.TestAndSaveStdRequest(7L, "int main(){}", "思路"));

        Map<String, Object> data = dataOf(result);
        assertThat(data.get("saved")).isEqualTo(true);
        assertThat((String) data.get("message")).contains("通过全部");
        // 用于保存前一致性校验的指纹必须来自验证开始时，而不是保存前再读一次
        verify(appService).saveStdTested(7L, "int main(){}", "思路", "same");
    }

    @Test
    void blankCodeIsRejectedBeforeVerification() {
        Result<Map<String, Object>> result = controller.testAndSaveStd(
                new ProblemController.TestAndSaveStdRequest(7L, "   ", ""));

        assertThat(result.getCode()).isNotEqualTo(200);
        verify(testDataGenerationService, never()).verifyStdAgainstCases(anyLong(), any());
    }
}
