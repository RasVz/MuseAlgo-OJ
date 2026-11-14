package com.musealgo.oj.ai.review;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.musealgo.oj.ai.ReviewApplicationService;
import com.musealgo.oj.ai.dto.*;
import com.musealgo.oj.ai.mapper.ReviewMapper;
import com.musealgo.oj.ai.quota.AiQuotaService;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReviewWorkerTest {
    @BeforeAll static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"review-test"),SubmissionReview.class);
    }
    @Test void databaseResultWriteFailureStillAccountsOneKnownSuccessfulModelCall() {
        ReviewMapper mapper=mock(ReviewMapper.class);ReviewRecordService records=mock(ReviewRecordService.class);
        AiQuotaService quota=mock(AiQuotaService.class);AiUsageRecorder usage=mock(AiUsageRecorder.class);
        var worker=new ReviewWorker(records,mapper,mock(ReviewApplicationService.class),quota,usage,false,1);
        var record=new SubmissionReview();record.setId(19L);record.setUserId(7L);record.setProblemId(100L);record.setPrivileged(false);
        when(mapper.update(isNull(),any(Wrapper.class))).thenThrow(new DataAccessResourceFailureException("synthetic DB unavailable"));
        var outcome=AiAskOutcome.success("完整回答",new AiTokenUsage(10,2,12),"prompt",AiCallSource.legacy("synthetic"));
        try {
            assertThrows(DataAccessResourceFailureException.class,()->worker.finish(record,"execution","SUCCEEDED",null,null,"完整回答","prompt",outcome,10));
            verify(quota,times(1)).consume(7L,12,false);
            verify(usage,times(1)).record(eq(7L),eq(100L),eq("REVIEW"),eq(10),eq(2),eq(false),eq(10L),eq("SUCCESS"),contains("reviewId=19"),any());
        }finally{worker.close();}
    }
}
