package com.musealgo.oj.ai.controller;
import com.musealgo.oj.ai.*;
import com.musealgo.oj.ai.dto.*;
import com.musealgo.oj.ai.quota.AiQuotaService;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import com.musealgo.oj.api.problem.ProblemTutoringQuery;
import com.musealgo.oj.common.security.AuthenticatedUser;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.concurrent.ExecutorService;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AiUsageAccountingTest {
    record Actor(Long id) implements AuthenticatedUser { public Long getId(){return id;} }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void run(AiAskOutcome outcome,int input,int output,boolean estimated,String status){
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new Actor(7L),null,List.of()));
        var ai=mock(AiService.class);var quota=mock(AiQuotaService.class);var usage=mock(AiUsageRecorder.class);var executor=mock(ExecutorService.class);
        doAnswer(i->{((Runnable)i.getArgument(0)).run();return null;}).when(executor).execute(any());
        when(ai.streamChatGuarded(anyLong(),any(),any(),anyBoolean(),any())).thenReturn(outcome);
        var controller=new AiController(ai,quota,usage,mock(AiChatSessionService.class),executor,mock(ProblemTutoringQuery.class));
        var request=new AiAskRequest();request.setProblemId(1L);request.setStudentQuestion("问题");controller.askAiStream(request);
        verify(usage).record(eq(7L),eq(1L),eq("ASK"),eq(input),eq(output),eq(estimated),anyLong(),eq(status),any(),any());
        if(!"SUCCESS".equals(status))verify(quota,never()).consume(anyLong(),anyInt(),anyBoolean());
    }
    @Test void configurationFailureBeforeSendingHasNoInventedTokenCost(){run(AiAskOutcome.fail("abcdef",null,"MODEL_UNAVAILABLE"),0,0,true,"FAIL");}
    @Test void sentFailureRetainsObservedEstimateWithoutChargingStudent(){run(new AiAskOutcome("FAIL","abcd",null,"abcdef",null,"NETWORK",1L,true),3,2,true,"FAIL");}
    @Test void cancellationUsesAvailableSupplierUsageWithoutStudentCharge(){run(new AiAskOutcome("CANCELLED","片段",new AiTokenUsage(12,8,20),"prompt",null,"CANCELLED",1L,true),12,8,false,"CANCELLED");}
}
