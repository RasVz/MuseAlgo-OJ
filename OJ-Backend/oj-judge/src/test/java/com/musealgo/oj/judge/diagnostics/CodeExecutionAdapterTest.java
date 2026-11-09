package com.musealgo.oj.judge.diagnostics;
import com.musealgo.oj.api.judge.*;
import com.musealgo.oj.api.problem.ProblemDebugReferenceQuery;
import com.musealgo.oj.judge.service.RunCodeService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CodeExecutionAdapterTest {
    final RunCodeService runner=mock(RunCodeService.class);
    final RunReceiptStore receipts=mock(RunReceiptStore.class);
    final ProblemDebugReferenceQuery references=mock(ProblemDebugReferenceQuery.class);
    @SuppressWarnings("unchecked") final ObjectProvider<RunUsageSink> telemetry=mock(ObjectProvider.class);
    final CodeExecutionAdapter adapter=new CodeExecutionAdapter(runner,receipts,references,telemetry);
    final CodeExecutionGateway.Request request=new CodeExecutionGateway.Request(7L,1L,"student","cpp","0","DIAGNOSTIC");
    Map<String,Object> output(String text,int status){return Map.of("status",status,"raw_stdout",text);}
    @BeforeEach void prepare(){when(references.find(1L,"0")).thenReturn(new ProblemDebugReferenceQuery.Reference(null,null));when(runner.executeCode(anyString(),anyString(),anyString())).thenReturn(output("1",3));}
    @Test void judgeInfrastructureErrorIsNeverAStudentCounterexample(){for(int status:List.of(13,14)){when(runner.executeCode(anyString(),anyString(),anyString())).thenReturn(output("",status));assertFalse(adapter.verify(request,null).confirmedFailure());}verifyNoInteractions(references);}
    @Test void messageOnlyErrorSurvivesEvidence(){when(runner.executeCode(anyString(),anyString(),anyString())).thenReturn(Map.of("status",5,"raw_stdout","","message","CPU time limit exceeded"));assertEquals("CPU time limit exceeded",adapter.verify(request,null).run().message());}
    @Test void legacyLongOfficialSampleCanBeRunManually(){assertEquals(3,adapter.run(new CodeExecutionGateway.Request(7L,1L,"student","cpp","0".repeat(5000),"MANUAL")).status());}
    @Test void guessedExpectedIsNotProof(){var result=adapter.verify(request,"0");assertEquals("AI_GUESS",result.expectationSource());assertFalse(result.confirmedFailure());}
    @Test void trustedReferenceComputesExpectedAndDoesNotLeakProgram(){when(references.find(1L,"0")).thenReturn(new ProblemDebugReferenceQuery.Reference(null,"PRIVATE_ORACLE"));when(runner.executeCode("PRIVATE_ORACLE","cpp","0")).thenReturn(output("0",3));var result=adapter.verify(request,"wrong");assertTrue(result.confirmedFailure());assertEquals("REFERENCE_PROGRAM",result.expectationSource());assertEquals("0",result.expectedOutput());assertFalse(result.toString().contains("PRIVATE_ORACLE"));}
    @Test void officialEmptyExpectedIsCompared(){when(references.find(1L,"0")).thenReturn(new ProblemDebugReferenceQuery.Reference("",null));assertTrue(adapter.verify(request,null).confirmedFailure());}
    @Test void compilerFailureIsNotInputCounterexample(){when(runner.executeCode(anyString(),anyString(),anyString())).thenReturn(output("",6));assertFalse(adapter.verify(request,null).confirmedFailure());verifyNoInteractions(references);}
    @Test void runtimeErrorIsConfirmedWithoutInventingExpected(){when(runner.executeCode(anyString(),anyString(),anyString())).thenReturn(output("",11));var result=adapter.verify(request,null);assertTrue(result.confirmedFailure());assertNull(result.expectedOutput());assertEquals("RUNTIME_ERROR",result.expectationSource());}
    @Test void truncatedOutputCannotConfirmMismatch(){when(runner.executeCode(anyString(),anyString(),anyString())).thenReturn(output("x".repeat(8193),3));when(references.find(1L,"0")).thenReturn(new ProblemDebugReferenceQuery.Reference("0",null));assertFalse(adapter.verify(request,null).confirmedFailure());}
    @Test void matchingReceiptAvoidsAnotherStudentRun(){var cached=new CodeExecutionGateway.Observation("receipt","0","1","","",3,"0.1",100,false);when(receipts.read(7L,1L,"student","cpp","receipt")).thenReturn(cached);when(references.find(1L,"0")).thenReturn(new ProblemDebugReferenceQuery.Reference("0",null));assertTrue(adapter.verify(request,null,List.of("receipt")).confirmedFailure());verifyNoInteractions(runner);}
    @Test void telemetryFailureDoesNotBreakExecution(){doThrow(new RuntimeException("statistics down")).when(telemetry).ifAvailable(any());assertEquals(3,adapter.run(request).status());}
}
