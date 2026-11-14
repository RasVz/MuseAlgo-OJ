package com.musealgo.oj.ai.review;

import cn.hutool.json.*;
import com.musealgo.oj.ai.*;
import com.musealgo.oj.ai.dto.*;
import com.musealgo.oj.ai.gateway.*;
import com.musealgo.oj.ai.guard.AiContestGuard;
import com.musealgo.oj.ai.mapper.ReviewMapper;
import com.musealgo.oj.ai.quota.AiQuotaService;
import com.musealgo.oj.ai.tutor.TutorMessageBuilder;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import com.musealgo.oj.api.problem.ProblemTutoringQuery;
import com.musealgo.oj.api.submission.SubmissionReviewQuery;
import com.musealgo.oj.common.exception.*;
import com.musealgo.oj.common.security.AuthenticatedUser;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 独立临时MySQL + 真实HTTP + 真实Mapper/后台任务；模型和身份均为合成替身。 */
@EnabledIfEnvironmentVariable(named="OJ_REVIEW_TEST_JDBC",matches="jdbc:mysql://127[.]0[.]0[.]1:13328/review_test.*")
@SpringBootTest(classes=ReviewRecordsIT.Fixture.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=${OJ_REVIEW_TEST_JDBC}","spring.datasource.username=root","spring.datasource.password=synthetic-review-only",
    "spring.flyway.enabled=false","spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:db/migration/V77__ai_submission_review.sql",
    "spring.main.banner-mode=off","logging.level.root=WARN","ai.review.worker.enabled=false",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
    "mybatis-plus.configuration.map-underscore-to-camel-case=true"
})
class ReviewRecordsIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReviewWorker worker;
    @Autowired ReviewMapper mapper;
    @Autowired ReviewRecordService records;
    @Autowired ReviewApplicationService reviews;
    @Autowired State state;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @BeforeEach void resetState() {
        jdbc.execute("TRUNCATE TABLE ai_submission_review");state.calls.set(0);state.slow=false;state.fail=false;state.outputLimit=false;state.providerCatchesCancel=false;state.currentStatus=1;state.currentScore=100;state.currentCode="int main(){}";state.systemTemplate="原模板";
        state.entered=new CountDownLatch(1);state.release=new CountDownLatch(1);clearInvocations(state.quota,state.usage);
        when(state.quota.tryAcquire(anyLong(),anyBoolean())).thenReturn(true);
    }
    JSONObject call(Long user,String method,String path,String body)throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ai/reviews"+path)).timeout(Duration.ofSeconds(10));
        if(user!=null)req.header("X-Review-Test-User",user.toString());
        req.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return new JSONObject(client.send(req.build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    long create(String key,long submission)throws Exception { return call(7L,"POST","",body(key,submission,"INITIAL",null,null)).getJSONObject("data").getLong("id"); }
    String body(String key,long submission,String type,Long parent,String question) {
        return new JSONObject().set("requestId",key).set("submissionId",submission).set("relationType",type)
                .set("parentReviewId",parent).set("studentQuestion",question).toString();
    }
    JSONObject detail(long id)throws Exception{return call(7L,"GET","/"+id,null).getJSONObject("data");}

    @Test void createsBeforeGenerationAndContinuesWithoutBrowserConnection()throws Exception {
        state.slow=true;long id=create("leave-page",1);assertEquals("QUEUED",detail(id).getStr("status"));assertEquals(0,state.calls.get());
        var pool=Executors.newSingleThreadExecutor();
        try {var job=pool.submit(()->worker.run(id));assertTrue(state.entered.await(3,TimeUnit.SECONDS));
            assertEquals("RUNNING",call(7L,"GET","/"+id+"/status",null).getJSONObject("data").getStr("status"));
            // 请求早已结束、没有浏览器连接，模型仍在后台执行。
            state.release.countDown();job.get(5,TimeUnit.SECONDS);
        }finally{state.release.countDown();pool.shutdownNow();}
        assertEquals("SUCCEEDED",detail(id).getStr("status"));assertEquals("分析结果",detail(id).getStr("answer"));
        assertEquals("int main(){}",detail(id).getStr("code"));assertEquals(1,state.calls.get());
        assertEquals(1,call(7L,"GET","?submissionId=1",null).getJSONObject("data").getLong("total"));
        assertFalse(detail(id).containsKey("inputJson"));assertFalse(detail(id).containsKey("privileged"));
        verify(state.quota,times(1)).consume(eq(7L),eq(12),eq(false));
    }
    @Test void concurrentCreationAndRepeatedDeliveryHaveOneModelInvocation()throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {Callable<Long> task=()->{gate.await();return create("same-request",1);};var a=pool.submit(task);var b=pool.submit(task);gate.countDown();
            long id=a.get(5,TimeUnit.SECONDS);assertEquals(id,b.get(5,TimeUnit.SECONDS));
            assertEquals(409,call(7L,"POST","",body("another-request",1,"INITIAL",null,null)).getInt("code"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_submission_review",Integer.class));
            worker.run(id);worker.run(id);assertEquals(1,state.calls.get());assertEquals(id,create("same-request",1));
            var changed=call(7L,"POST","",body("same-request",2,"INITIAL",null,null));assertEquals(409,changed.getInt("code"));
        }finally{pool.shutdownNow();}
    }
    @Test void followupAndRetryKeepPriorRecordsAndUseServerAnswer()throws Exception {
        long id=create("original",1);worker.run(id);
        long child=call(7L,"POST","",body("followup",1,"FOLLOW_UP",id,"解释第二点")).getJSONObject("data").getLong("id");
        worker.run(child);assertEquals("分析结果",detail(id).getStr("answer"));assertEquals(id,detail(child).getLong("parentReviewId"));
        assertTrue(state.lastPrompt.contains("分析结果"));assertTrue(state.lastPrompt.contains("解释第二点"));
        state.fail=true;long failed=create("failed",2);worker.run(failed);
        assertEquals("FAILED",detail(failed).getStr("status"));assertTrue(detail(failed).getStr("answer").contains("部分"));
        state.fail=false;long retry=call(7L,"POST","",body("retry",2,"RETRY",failed,null)).getJSONObject("data").getLong("id");worker.run(retry);
        assertEquals("FAILED",detail(failed).getStr("status"));assertEquals("SUCCEEDED",detail(retry).getStr("status"));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM ai_submission_review",Integer.class));
    }
    @Test void cancellationWinsAgainstLateSuccessfulReplyAndQueuedWorkNeverCallsModel()throws Exception {
        long queued=create("cancel-queued",1);call(7L,"POST","/"+queued+"/cancel","{}");worker.run(queued);assertEquals(0,state.calls.get());
        state.slow=true;long running=create("cancel-running",2);var pool=Executors.newSingleThreadExecutor();
        try {var job=pool.submit(()->worker.run(running));assertTrue(state.entered.await(3,TimeUnit.SECONDS));
            assertEquals("CANCELLING",call(7L,"POST","/"+running+"/cancel","{}").getJSONObject("data").getStr("status"));
            assertEquals(409,call(7L,"POST","",body("too-early-retry",2,"RETRY",running,null)).getInt("code"));
            assertEquals(409,call(7L,"POST","",body("same-during-stop",2,"INITIAL",null,null)).getInt("code"));assertEquals(1,state.calls.get());
            state.release.countDown();job.get(5,TimeUnit.SECONDS);
        }finally{state.release.countDown();pool.shutdownNow();}
        assertEquals("CANCELLED",detail(running).getStr("status"));assertFalse(detail(running).getStr("answer").isBlank());
    }
    @Test void ownerContestExamAndParentBoundariesAreCheckedOnServer()throws Exception {
        assertEquals(401,call(null,"GET","",null).getInt("code"));
        assertEquals(403,call(7L,"POST","",body("contest",9,"INITIAL",null,null)).getInt("code"));
        assertEquals("AI_REVIEW_EXAM_BLOCKED",call(7L,"POST","",body("exam",8,"INITIAL",null,null)).getStr("errorCode"));
        assertEquals(403,call(7L,"POST","",body("other",6,"INITIAL",null,null)).getInt("code"));
        long id=create("mine",1);worker.run(id);
        assertEquals(404,call(8L,"GET","/"+id,null).getInt("code"));assertEquals(404,call(8L,"POST","/"+id+"/cancel","{}").getInt("code"));
        assertEquals(404,call(8L,"POST","",body("foreign-parent",1,"FOLLOW_UP",id,"问题")).getInt("code"));
        assertEquals(400,call(7L,"POST","",body("wrong-submission",2,"FOLLOW_UP",id,"问题")).getInt("code"));
        assertEquals(0,call(8L,"GET","",null).getJSONObject("data").getLong("total"));
        assertEquals(100,call(7L,"GET","?pageSize=999",null).getJSONObject("data").getInt("pageSize"));
        assertEquals(1,call(7L,"GET","?pageSize=-1",null).getJSONObject("data").getInt("pageSize"));
    }
    @Test void listFindsProblemNumberAndKeepsOwnerFilterForNumericOrTitleSearch()throws Exception {
        create("search-by-number",1);
        assertEquals(1,call(7L,"GET","?keyword=P100",null).getJSONObject("data").getLong("total"));
        assertEquals(1,call(7L,"GET","?keyword=100",null).getJSONObject("data").getLong("total"));
        assertEquals(0,call(7L,"GET","?keyword=P999",null).getJSONObject("data").getLong("total"));
        assertEquals(0,call(8L,"GET","?keyword=P100",null).getJSONObject("data").getLong("total"));
    }

    @Test void unavailableQuotaAndInvalidInputDoNotLeavePhantomRecords()throws Exception {
        when(state.quota.tryAcquire(anyLong(),anyBoolean())).thenReturn(false);
        assertEquals(429,call(7L,"POST","",body("no-quota",1,"INITIAL",null,null)).getInt("code"));
        assertEquals(400,call(7L,"POST","","{\"requestId\":\"bad key\",\"submissionId\":1}").getInt("code"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ai_submission_review",Integer.class));assertEquals(0,state.calls.get());
    }
    @Test void inputSnapshotSurvivesRejudgeAndCodeMutationRefusesGeneration()throws Exception {
        long id=create("snapshot",1);state.currentStatus=2;state.currentScore=37;
        worker.run(id);assertEquals(100,detail(id).getInt("score"));assertTrue(state.lastPrompt.contains("状态: AC"));
        long changed=create("changed-code",2);state.currentCode="different code";
        worker.run(changed);assertEquals("FAILED",detail(changed).getStr("status"));assertEquals(1,state.calls.get());
    }

    @Test
    @EnabledIfEnvironmentVariable(named="OJ_REVIEW_BROWSER_SCRIPT",matches=".+")
    void browserPagesUseActualRecordApiAndDatabase()throws Exception {
        var recovery=new ReviewWorker(records,mapper,reviews,state.quota,state.usage,true,1);
        var timer=Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(recovery::recover,0,100,TimeUnit.MILLISECONDS);
        try {
            var command=new ProcessBuilder("node",System.getenv("OJ_REVIEW_BROWSER_SCRIPT"));
            command.environment().put("OJ_REVIEW_HTTP_PORT",Integer.toString(port));
            command.redirectErrorStream(true);
            var process=command.start();
            boolean ended=process.waitFor(90,TimeUnit.SECONDS);
            if(!ended)process.destroyForcibly();
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(ended,output);assertEquals(0,process.exitValue(),output);
            assertEquals(3,state.calls.get());assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM ai_submission_review",Integer.class));
            System.out.println(output);
        }finally{timer.shutdownNow();recovery.close();}
    }

    @Test void localOutputLimitKeepsPartialAndEstimatedUsageInsteadOfPretendingNoCall()throws Exception {
        state.outputLimit=true;long id=create("output-limit",1);worker.run(id);
        assertEquals("FAILED",detail(id).getStr("status"));assertEquals("AI_OUTPUT_LIMIT",detail(id).getStr("errorCode"));
        assertEquals("部分",detail(id).getStr("answer"));
        assertTrue(mapper.full(id).getPromptTokens()>0);assertTrue(mapper.full(id).getTokensEstimated());
        verify(state.usage).record(eq(7L),eq(100L),eq("REVIEW"),intThat(x->x>0),anyInt(),eq(true),anyLong(),eq("FAIL"),contains("上游费用未知"),isNull());
    }

    @Test void providerReturningOversizedCancelledOutcomeCannotBypassStoredAnswerLimit()throws Exception {
        state.outputLimit=true;state.providerCatchesCancel=true;long id=create("provider-output-limit",1);worker.run(id);
        assertEquals("FAILED",detail(id).getStr("status"));assertEquals("AI_OUTPUT_LIMIT",detail(id).getStr("errorCode"));
        assertEquals("部分",detail(id).getStr("answer"));
        assertTrue(mapper.full(id).getCompletionTokens()>0); // 估算仍反映上游实际返回量，存储只留接纳部分。
    }

    @Test void queuedTaskUsesTheTemplateFrozenAtCreation()throws Exception {
        long id=create("frozen-prompt",1);state.systemTemplate="新激活模板";worker.run(id);
        assertEquals("SUCCEEDED",detail(id).getStr("status"));assertTrue(state.lastPrompt.contains("原模板"));assertFalse(state.lastPrompt.contains("新激活模板"));
    }

    @Test void lostDispatchIsRecoveredAndInterruptedRunningWorkIsNotRecalled()throws Exception {
        long queued=create("lost-dispatch",1);
        long stale=create("stale-running",2);
        jdbc.update("UPDATE ai_submission_review SET status='RUNNING',execution_id='stale',start_time=DATE_SUB(NOW(),INTERVAL 7 MINUTE),answer='中断前部分' WHERE id=?",stale);
        var recovery=new ReviewWorker(records,mapper,reviews,state.quota,state.usage,true,1);
        try {
            recovery.recover();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertEquals("SUCCEEDED",detail(queued).getStr("status")));
            assertEquals("FAILED",detail(stale).getStr("status"));assertEquals("AI_REVIEW_INTERRUPTED",detail(stale).getStr("errorCode"));
            assertEquals("中断前部分",detail(stale).getStr("answer"));assertEquals(1,state.calls.get());
            worker.run(stale);assertEquals(1,state.calls.get());
        }finally{recovery.close();}
    }

    record Actor(Long userId) implements AuthenticatedUser { public Long getId(){return userId;} }
    static class State {
        final AiQuotaService quota=mock(AiQuotaService.class);final AiUsageRecorder usage=mock(AiUsageRecorder.class);
        final AtomicInteger calls=new AtomicInteger();volatile boolean slow,fail,outputLimit,providerCatchesCancel;volatile String lastPrompt;volatile String systemTemplate="原模板";
        volatile byte currentStatus=1;volatile int currentScore=100;volatile String currentCode="int main(){}";
        volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    }
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude={SecurityAutoConfiguration.class,UserDetailsServiceAutoConfiguration.class})
    @MapperScan(basePackageClasses=ReviewMapper.class)
    @Import({ReviewRecordController.class,ReviewRecordService.class,ReviewWorker.class,ReviewApplicationService.class})
    static class Fixture {
        @Bean State state(){return new State();}
        @Bean AiQuotaService quota(State s){return s.quota;}
        @Bean AiUsageRecorder usage(State s){return s.usage;}
        @Bean AiContestGuard contest(){return new AiContestGuard();}
        @Bean GlobalExceptionHandler errors(){return new GlobalExceptionHandler();}
        @Bean TutorMessageBuilder messages(State s){var registry=mock(PromptRegistry.class);when(registry.render(anyString())).thenAnswer(i->s.systemTemplate);return new TutorMessageBuilder(registry);}
        @Bean ProblemTutoringQuery problems(){return id->new ProblemTutoringQuery.Context(id,"合成题目","公开合成题面");}
        @Bean SubmissionReviewQuery submissions(State s){return id->Optional.of(new SubmissionReviewQuery.SubmissionReviewData(
                id,id==6?8L:7L,100L,s.currentStatus,"cpp",s.currentCode,s.currentScore,1,1024,null,id==9?9L:null,List.of(),id==8?88L:null));}
        @Bean AiGateway gateway(State s){var gateway=mock(AiGateway.class);var session=mock(AiGateway.TeachingSession.class);
            when(gateway.openTeachingSession()).thenReturn(session);
            when(session.stream(any(),nullable(cn.hutool.json.JSONArray.class),any())).thenAnswer(i->{
            s.calls.incrementAndGet();s.lastPrompt=i.getArgument(0).toString();Consumer<String> sink=i.getArgument(2);
            sink.accept("部分");if(s.outputLimit) {
                try { sink.accept("x".repeat(200001)); }
                catch(AiStreamControl.Cancelled c) {
                    if(!s.providerCatchesCancel) throw c;
                    return new AiStreamResult("部分"+"x".repeat(200001),null,AiCallSource.legacy("synthetic"),false,true,"CANCELLED","cancelled",true);
                }
            }s.entered.countDown();if(s.slow)s.release.await(5,TimeUnit.SECONDS);
            if(s.fail)return new AiStreamResult("部分",new AiTokenUsage(10,2,12),AiCallSource.legacy("synthetic"),false,true,"TIMEOUT","timeout",true);
            // 故意模拟取消回调之后仍迟到的成功结果，验证不能覆盖CANCELLED。
            return AiStreamResult.success("分析结果",new AiTokenUsage(10,2,12),AiCallSource.legacy("synthetic"),true);
        });return gateway;}
        @Bean MybatisPlusInterceptor pagination(){var i=new MybatisPlusInterceptor();i.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));return i;}
        @Bean FilterRegistrationBean<OncePerRequestFilter> actor(){return new FilterRegistrationBean<>(new OncePerRequestFilter(){
            protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
                String id=req.getHeader("X-Review-Test-User");if(id!=null)SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new Actor(Long.valueOf(id)),null,List.of()));
                try{chain.doFilter(req,res);}finally{SecurityContextHolder.clearContext();}
            }
        });}
    }
}
