package com.musealgo.oj.ai.controller;

import com.musealgo.oj.ai.HintLadderQueryService;
import com.musealgo.oj.ai.TeacherWorkbenchService;
import com.musealgo.oj.ai.dto.AiCallSource;
import com.musealgo.oj.ai.dto.AiCompletionResult;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import com.musealgo.oj.common.api.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 教师侧 8 个 AI 入口的输入边界：缺必填/超长必须返回 AI_INPUT_INVALID，且不得触达模型。
 * 反例覆盖"空请求体"与"刚超过上限"两类，避免把无效输入送上游产生费用。
 */
class TeacherAiInputBoundaryTest {

    @FunctionalInterface
    interface Call {
        Result<String> invoke(TeacherAiController controller, Map<String, String> body);
    }

    private static TeacherAiController controller(TeacherWorkbenchService workbench) {
        return new TeacherAiController(workbench, mock(AiUsageRecorder.class));
    }

    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("draft-problem", (Call) (c, b) -> c.draftProblem(b)),
                Arguments.of("testcase", (Call) (c, b) -> c.testcase(b)),
                Arguments.of("class-insight", (Call) (c, b) -> c.classInsight(b)),
                Arguments.of("workbench-statement", (Call) (c, b) -> c.wbStatement(b)),
                Arguments.of("workbench-std", (Call) (c, b) -> c.wbStd(b)),
                Arguments.of("workbench-testdata", (Call) (c, b) -> c.wbTestdata(b)),
                Arguments.of("workbench-generator", (Call) (c, b) -> c.wbGenerator(b)),
                Arguments.of("workbench-rename", (Call) (c, b) -> c.wbRename(b))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void missingRequiredInputIsRejectedWithoutCallingTheModel(String name, Call endpoint) {
        TeacherWorkbenchService workbench = mock(TeacherWorkbenchService.class);

        Result<String> response = endpoint.invoke(controller(workbench), Map.of());

        assertEquals(400, response.getCode());
        assertEquals("AI_INPUT_INVALID", response.getErrorCode());
        verifyNoInteractions(workbench);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void blankRequiredInputIsRejectedWithoutCallingTheModel(String name, Call endpoint) {
        TeacherWorkbenchService workbench = mock(TeacherWorkbenchService.class);
        Map<String, String> blank = Map.of(
                "requirements", "   ", "statement", " ", "statsText", "\n", "knowledgePoint", "  ");

        Result<String> response = endpoint.invoke(controller(workbench), blank);

        assertEquals(400, response.getCode());
        assertEquals("AI_INPUT_INVALID", response.getErrorCode());
        verifyNoInteractions(workbench);
    }

    @Test
    void oversizedInputIsRejectedBeforeReachingTheModel() {
        TeacherWorkbenchService workbench = mock(TeacherWorkbenchService.class);
        TeacherAiController controller = controller(workbench);
        String tooLongStatement = "x".repeat(8001);

        Result<String> statement = controller.wbStd(Map.of("statement", tooLongStatement));
        Result<String> requirements = controller.draftProblem(Map.of("requirements", "x".repeat(4001)));
        Result<String> knowledgePoint = controller.wbStatement(Map.of("knowledgePoint", "x".repeat(201)));

        for (Result<String> response : new Result[]{statement, requirements, knowledgePoint}) {
            assertEquals(400, response.getCode());
            assertEquals("AI_INPUT_INVALID", response.getErrorCode());
        }
        verifyNoInteractions(workbench);
    }

    @Test
    void caseCountMustBeAnIntegerWithinRange() {
        TeacherWorkbenchService workbench = mock(TeacherWorkbenchService.class);
        TeacherAiController controller = controller(workbench);
        String statement = "synthetic statement";

        for (String bad : new String[]{"abc", "0", "21"}) {
            Result<String> response = controller.wbTestdata(Map.of("statement", statement, "caseCount", bad));
            assertEquals(400, response.getCode());
            assertEquals("AI_INPUT_INVALID", response.getErrorCode());
        }
        Result<String> generator = controller.wbGenerator(Map.of("statement", statement, "caseCount", "51"));
        assertEquals(400, generator.getCode());
        verifyNoInteractions(workbench);
    }

    @Test
    void validInputStillReachesTheModel() {
        TeacherWorkbenchService workbench = mock(TeacherWorkbenchService.class);
        when(workbench.teacherWorkbenchJsonDetailed(anyString(), anyString()))
                .thenReturn(AiCompletionResult.failure(AiCallSource.off(), "TIMEOUT", "synthetic"));
        TeacherAiController controller = controller(workbench);

        Result<String> response = controller.wbStd(Map.of("statement", "synthetic statement"));

        assertNotEquals(400, response.getCode());
        assertEquals(504, response.getCode());
        verify(workbench).teacherWorkbenchJsonDetailed(anyString(), anyString());
    }
}
