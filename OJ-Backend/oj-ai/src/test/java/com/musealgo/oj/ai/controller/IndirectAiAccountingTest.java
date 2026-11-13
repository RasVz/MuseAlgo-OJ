package com.musealgo.oj.ai.controller;
import com.musealgo.oj.ai.*;
import com.musealgo.oj.ai.dto.*;
import com.musealgo.oj.ai.gateway.AiGateway;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class IndirectAiAccountingTest {
    final AiGateway gateway=mock(AiGateway.class);final TeacherWorkbenchService teacher=mock(TeacherWorkbenchService.class);final AiUsageRecorder usage=mock(AiUsageRecorder.class);
    final AiService service=new AiService(gateway,null,null,null,null,teacher,null,null,null,usage);
    @Test void backgroundAdviceUsesExplicitOwnerAndSupplierTokens(){when(gateway.completeDetailed(any(),eq(false))).thenReturn(AiCompletionResult.success("advice",new AiTokenUsage(10,5,15),AiCallSource.legacy("synthetic")));service.chatForUser(7L,"rules","stats");verify(usage).record(eq(7L),isNull(),eq("ADVICE"),eq(10),eq(5),eq(false),anyLong(),eq("SUCCESS"),isNull(),any());}
    @Test void cachedAdviceHasNoInventedSupplierCost(){when(gateway.completeDetailed(any(),eq(false))).thenReturn(new AiCompletionResult("cached",null,AiCallSource.legacy("synthetic"),null,null,true));service.chatForUser(7L,"rules","stats");verify(usage).record(eq(7L),isNull(),eq("ADVICE"),eq(0),eq(0),eq(false),anyLong(),eq("SUCCESS"),contains("缓存命中"),any());}
    @Test void teacherControllerDoesNotEstimateCachedResponseAsBilledTokens(){when(teacher.teacherChatDetailed(anyString(),anyString())).thenReturn(new AiCompletionResult("cached",null,AiCallSource.legacy("synthetic"),null,null,true));new TeacherAiController(teacher,usage).draftProblem(Map.of("requirements","生成求和题"));verify(usage).record(isNull(),isNull(),eq("DRAFT"),eq(0),eq(0),eq(false),anyLong(),eq("SUCCESS"),isNull(),any());}
}
