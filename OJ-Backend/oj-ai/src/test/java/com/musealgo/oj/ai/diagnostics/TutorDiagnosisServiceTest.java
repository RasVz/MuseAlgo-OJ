package com.musealgo.oj.ai.diagnostics;
import cn.hutool.json.*;
import com.musealgo.oj.ai.dto.*;
import com.musealgo.oj.ai.gateway.*;
import com.musealgo.oj.ai.reference.TutorReferenceService;
import com.musealgo.oj.ai.tutor.TutorMessageBuilder;
import com.musealgo.oj.api.judge.CodeExecutionGateway;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class TutorDiagnosisServiceTest {
    final AiGateway gateway=mock(AiGateway.class);final AiGateway.TeachingSession session=mock(AiGateway.TeachingSession.class);
    final CodeExecutionGateway execution=mock(CodeExecutionGateway.class);final DiagnosisSettings settings=mock(DiagnosisSettings.class);
    final DiagnosticStats stats=mock(DiagnosticStats.class);final ExecutorService pool=Executors.newSingleThreadExecutor();
    final TutorDiagnosisService diagnosis=new TutorDiagnosisService(gateway,execution,settings,stats,mock(TutorReferenceService.class),pool);
    final AiAskRequest request=new AiAskRequest();
    final AiCallSource source=AiCallSource.unavailable();
    final CodeExecutionGateway.Observation observation=new CodeExecutionGateway.Observation("id","0","1","","",3,"0.1",10,false);
    @BeforeEach void prepare(){request.setProblemId(1L);request.setStudentCode("student");request.setStudentQuestion("debug");when(gateway.openTeachingSession()).thenReturn(session);when(settings.maxCases()).thenReturn(1);when(settings.waitSeconds()).thenReturn(2);}
    @AfterEach void close(){pool.shutdownNow();}
    JSONArray messages(){return new JSONArray().put(TutorMessageBuilder.message("system","rules")).put(TutorMessageBuilder.message("user","materials"));}
    AiStreamResult tools(String name,int count){List<AiStreamResult.ToolCall> calls=new ArrayList<>();for(int i=0;i<count;i++)calls.add(new AiStreamResult.ToolCall("call"+i,name,"{\"input\":\"0\"}"));return new AiStreamResult("",new AiTokenUsage(12,8,20),source,true,false,null,null,true,calls,false);}
    AiStreamResult answer(){return AiStreamResult.success("answer",new AiTokenUsage(10,5,15),source,true);}
    @Test void oversizedOfficialExpectedDoesNotAbortAnswerOrProduceIncompleteCase(){when(session.stream(any(),any(),any())).thenReturn(tools("verify_case",1),answer());when(execution.verify(any(),nullable(String.class),any())).thenReturn(new CodeExecutionGateway.Verification(observation,"0".repeat(9000),"OFFICIAL_SAMPLE",true,"verified"));var events=new ArrayList<String>();assertTrue(diagnosis.stream(7L,request,messages(),new AiStreamControl(text->{},(name,data)->events.add(name))).success());assertFalse(events.contains("counterexample"));}
    @Test void textBeforeToolIsPreservedInCombinedAnswer(){var initial=tools("verify_case",1);when(session.stream(any(),any(),any())).thenReturn(new AiStreamResult("checking",initial.usage(),source,true,true,null,null,true,initial.toolCalls(),false),answer());when(execution.verify(any(),nullable(String.class),any())).thenReturn(new CodeExecutionGateway.Verification(observation,null,"NONE",false,"unknown"));assertEquals("checking\n\nanswer",diagnosis.stream(7L,request,messages(),text->{}).content());}
    @Test void directAnswerDoesNotExecuteAnything(){when(session.stream(any(),any(),any())).thenReturn(answer());assertTrue(diagnosis.stream(7L,request,messages(),text->{}).success());verifyNoInteractions(execution);verify(session).stream(any(),any(),any());}
    @Test void enforcesCaseBudgetAndDisablesToolsOnFinalRound(){when(session.stream(any(),any(),any())).thenReturn(tools("verify_case",2),answer());when(execution.verify(any(),nullable(String.class),any())).thenReturn(new CodeExecutionGateway.Verification(observation,"0","OFFICIAL_SAMPLE",true,"verified"));var messages=messages();var result=diagnosis.stream(7L,request,messages,text->{});assertEquals(22,result.usage().promptTokens());assertEquals(13,result.usage().completionTokens());verify(execution,times(1)).verify(any(),nullable(String.class),any());verify(session,times(1)).stream(any(),isNull(),any());assertTrue(messages.toString().contains("预算已用完"));}
    @Test void unsupportedToolsFallsBackOnceToPlainAnswer(){when(session.stream(any(),any(),any())).thenReturn(AiStreamResult.failure(source,false,"TOOLS_UNSUPPORTED","unsupported"),answer());assertTrue(diagnosis.stream(7L,request,messages(),text->{}).success());verifyNoInteractions(execution);verify(session).stream(any(),isNull(),any());}
    @Test void invalidToolNeverRunsCode(){when(session.stream(any(),any(),any())).thenReturn(tools("delete_everything",1),answer());assertTrue(diagnosis.stream(7L,request,messages(),text->{}).success());verifyNoInteractions(execution);}
    @Test void failedVerificationStillAnswersWithoutInventedCounterexample(){when(session.stream(any(),any(),any())).thenReturn(tools("verify_case",1),answer());when(execution.verify(any(),nullable(String.class),any())).thenThrow(new RuntimeException("judge unavailable"));var events=new ArrayList<String>();var result=diagnosis.stream(7L,request,messages(),new AiStreamControl(text->{},(name,data)->events.add(name)));assertTrue(result.success());assertFalse(events.contains("counterexample"));assertEquals(35,result.usage().totalTokens());}
    @Test void cancellationBetweenRoundsPreservesFirstRoundUsage(){when(session.stream(any(),any(),any())).thenReturn(tools("verify_case",1));var control=new AiStreamControl(text->{},(name,data)->{if("verifying".equals(((Map<?,?>)data).get("phase")))throw new AiStreamControl.Cancelled();});var result=diagnosis.stream(7L,request,messages(),control);assertEquals("CANCELLED",result.errorType());assertEquals(20,result.usage().totalTokens());verifyNoInteractions(execution);}
    @Test void unknownUsageInOneRoundIsEstimatedAndAddedToKnownRound(){var unknown=new AiStreamResult("answer",null,source,true,true,null,null,true);when(session.stream(any(),any(),any())).thenReturn(tools("verify_case",1),unknown);when(execution.verify(any(),nullable(String.class),any())).thenReturn(new CodeExecutionGateway.Verification(observation,null,"NONE",false,"not confirmed"));var result=diagnosis.stream(7L,request,messages(),text->{});assertTrue(result.usageEstimated());assertTrue(result.usage().promptTokens()>12);assertTrue(result.usage().completionTokens()>8);}
    @Test void stopCancelsRunningEvidenceAndDoesNotStartAnotherModelRound() throws Exception {
        var started=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        when(session.stream(any(),any(),any())).thenReturn(tools("verify_case",1));
        when(execution.verify(any(),nullable(String.class),any())).thenAnswer(i->{started.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException stopped){interrupted.countDown();throw stopped;}return null;});
        var control=new AiStreamControl(text->{});
        var result=CompletableFuture.supplyAsync(()->diagnosis.stream(7L,request,messages(),control));
        assertTrue(started.await(2,TimeUnit.SECONDS));control.cancel();
        assertEquals("CANCELLED",result.get(2,TimeUnit.SECONDS).errorType());assertTrue(interrupted.await(2,TimeUnit.SECONDS));
        verify(session,times(1)).stream(any(),any(),any());
    }
    @Test void waitTimeoutCancelsEvidenceAndContinuesAnswering(){when(settings.waitSeconds()).thenReturn(0);when(session.stream(any(),any(),any())).thenReturn(tools("verify_case",1),answer());var messages=messages();assertTrue(diagnosis.stream(7L,request,messages,text->{}).success());verifyNoInteractions(execution);assertTrue(messages.toString().contains("验证未完成"));}
}
