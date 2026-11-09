package com.musealgo.oj.ai;
import com.musealgo.oj.ai.dto.*;
import com.musealgo.oj.ai.gateway.*;
import com.musealgo.oj.ai.guard.AiContestGuard;
import com.musealgo.oj.ai.quota.AiQuotaService;
import com.musealgo.oj.ai.tutor.TutorMessageBuilder;
import com.musealgo.oj.api.problem.ProblemTutoringQuery;
import com.musealgo.oj.api.submission.SubmissionReviewQuery;
import com.musealgo.oj.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AiServiceReviewTest {
    final AiGateway gateway=mock(AiGateway.class);
    final AiQuotaService quota=mock(AiQuotaService.class);
    final SubmissionReviewQuery submissions=mock(SubmissionReviewQuery.class);
    final ProblemTutoringQuery problems=mock(ProblemTutoringQuery.class);
    final ReviewApplicationService service=new ReviewApplicationService(gateway,quota,new AiContestGuard(),submissions,problems,new TutorMessageBuilder(mock(PromptRegistry.class)));
    AiReviewRequest request() { var r=new AiReviewRequest();r.setSubmissionId(1L);return r; }
    void data(Long contest, byte status) {
        when(submissions.findById(1L)).thenReturn(Optional.of(new SubmissionReviewQuery.SubmissionReviewData(1L,10L,100L,status,"cpp","int main(){}",0,12,1024,"error",contest,List.of(new SubmissionReviewQuery.CaseStatusCount(2,1)))));
    }
    @Test void reviewPromptContainsCodeFormalProblemAndStatsIncludingAC() {
        data(null,(byte)1);when(problems.load(100L)).thenReturn(new ProblemTutoringQuery.Context(100L,"题","正式题面"));when(quota.tryAcquire(10L,false)).thenReturn(true);
        when(gateway.stream(any(),any())).thenAnswer(i->{String prompt=i.getArgument(0).toString();assertTrue(prompt.contains("WA×1"));assertTrue(prompt.contains("状态: AC"));assertTrue(prompt.contains("正式题面"));assertTrue(prompt.contains("int main()"));return AiStreamResult.success("解释",null,null,true);});
        var request=request();assertEquals("SUCCESS",service.stream(10L,request,service.prepare(10L,request),false,x->{}).status());
    }
    @Test void actualContestSubmissionCannotBeSpoofedAsPracticeEvenAfterContest() {
        data(99L,(byte)2);var request=request();var prepared=service.prepare(10L,request);
        assertEquals("CONTEST_BLOCKED",service.stream(10L,request,prepared,true,x->{}).status());verifyNoInteractions(gateway,problems,quota);
    }
    @Test void explicitContestContextAlsoBlocksPracticeSubmission() {
        data(null,(byte)2);var request=request();request.setContestId(99L);
        assertEquals("CONTEST_BLOCKED",service.stream(10L,request,service.prepare(10L,request),false,x->{}).status());verifyNoInteractions(gateway,problems);
    }
    @Test void quotaRejectsWithoutModel() {
        data(null,(byte)2);var request=request();var prepared=service.prepare(10L,request);
        assertEquals("QUOTA_REJECTED",service.stream(10L,request,prepared,false,x->{}).status());verifyNoInteractions(gateway);
    }
    @Test void missingAndOtherUsersSubmissionRemainRejected() {
        assertEquals("AI_REVIEW_SUBMISSION_NOT_FOUND",assertThrows(BusinessException.class,()->service.prepare(10L,request())).getErrorCode().key());
        data(null,(byte)2);assertEquals("ACCESS_DENIED",assertThrows(BusinessException.class,()->service.prepare(20L,request())).getErrorCode().key());verifyNoInteractions(problems,gateway);
    }
    @Test void pendingSubmissionCannotBeReviewed() { data(null,(byte)0);assertThrows(BusinessException.class,()->service.prepare(10L,request())); }
    @Test void followupTreatsClientPreviousAnswerAsUntrustedMaterial() {
        data(null,(byte)2);when(problems.load(100L)).thenReturn(new ProblemTutoringQuery.Context(100L,"题","内容"));when(quota.tryAcquire(10L,false)).thenReturn(true);
        when(gateway.stream(any(),any())).thenAnswer(i->{var m=(cn.hutool.json.JSONArray)i.getArgument(0);assertEquals("user",m.getJSONObject(1).getStr("role"));assertTrue(m.getJSONObject(1).getStr("content").contains("前次分析"));assertFalse(m.getJSONObject(0).getStr("content").contains("前次分析"));return AiStreamResult.success("解释",null,null,true);});
        var request=request();request.setPreviousAnswer("前次分析");request.setStudentQuestion("解释第二点");service.stream(10L,request,service.prepare(10L,request),false,x->{});
    }
}
