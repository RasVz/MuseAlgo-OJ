package com.musealgo.oj.ai;

import cn.hutool.json.*;
import com.musealgo.oj.ai.controller.AiController;
import com.musealgo.oj.ai.dto.*;
import com.musealgo.oj.ai.gateway.*;
import com.musealgo.oj.ai.guard.AiContestGuard;
import com.musealgo.oj.ai.mapper.PromptTemplateMapper;
import com.musealgo.oj.ai.network.AiEndpointGuard;
import com.musealgo.oj.ai.quota.AiQuotaService;
import com.musealgo.oj.ai.runtime.*;
import com.musealgo.oj.ai.tutor.TutorMessageBuilder;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import com.musealgo.oj.api.problem.ProblemTutoringQuery;
import com.musealgo.oj.api.submission.SubmissionReviewQuery;
import com.musealgo.oj.common.exception.*;
import com.musealgo.oj.common.security.AuthenticatedUser;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真正的 Servlet HTTP/SSE → 编排 → 网关 → 本机兼容上游；存储/身份使用合成替身。 */
@SpringBootTest(classes=OpenTutorHttpTest.Fixture.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.main.banner-mode=off","logging.level.root=WARN"})
@DirtiesContext
class OpenTutorHttpTest {
    @LocalServerPort int port;
    @Autowired State state;
    final HttpClient client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    @BeforeEach void resetFixture(){state.history.clear();state.requests.clear();clearInvocations(state.usage);}
    @Test void httpConversationContestReviewAndOutputLimit() throws Exception {
        var complete=call("POST","/ai/ask/stream","{\"problemId\":1,\"studentQuestion\":\"完整讲解\",\"helpDirection\":\"FULL_SOLUTION\",\"studentCode\":\"int x = 7;\"}");
        assertTrue(complete.contains("event:done"),complete);assertTrue(complete.contains("参考实现"));
        var upstream=state.requests.get(0);assertEquals(512,upstream.getInt("max_tokens"));assertEquals("system",upstream.getJSONArray("messages").getJSONObject(0).getStr("role"));
        assertFalse(upstream.getJSONArray("messages").getJSONObject(0).getStr("content").contains("int x = 7"));assertTrue(upstream.toString().contains("服务端正式题面"));
        assertTrue(call("GET","/ai/conversation?problemId=1",null).contains("参考实现"));
        call("DELETE","/ai/conversation?problemId=1",null);assertFalse(call("GET","/ai/conversation?problemId=1",null).contains("参考实现"));
        int before=state.requests.size();
        var blocked=call("POST","/ai/ask/stream","{\"problemId\":1,\"contestId\":9,\"studentQuestion\":\"问题\"}");assertTrue(blocked.contains("AI_CONTEST_BLOCKED"));assertFalse(blocked.contains("event:done"));assertEquals(before,state.requests.size());
        var review=call("POST","/ai/review","{\"submissionId\":9}");assertTrue(review.contains("AI_CONTEST_BLOCKED"));assertEquals(before,state.requests.size());
        var ac=call("POST","/ai/review","{\"submissionId\":1}");assertTrue(ac.contains("event:done"));
        var truncated=call("POST","/ai/ask/stream","{\"problemId\":1,\"studentQuestion\":\"truncate\"}");assertTrue(truncated.contains("AI_OUTPUT_LIMIT"));assertFalse(truncated.contains("event:done"));
        var denied=call("POST","/ai/ask/stream","{\"problemId\":999,\"studentQuestion\":\"问题\"}");assertTrue(denied.contains("ACCESS_DENIED"));
        var invalid=call("POST","/ai/ask/stream","{\"problemId\":1,\"studentQuestion\":\"问题\",\"helpDirection\":\"UNKNOWN\"}");assertFalse(invalid.contains("event:done"));
        String output=System.getenv("OJ_TEST_OUTPUT_DIR");
        if(output!=null){var directory=Path.of(output);Files.createDirectories(directory);Files.writeString(directory.resolve("http-success.sse"),complete);Files.writeString(directory.resolve("http-contest-blocked.sse"),blocked);Files.writeString(directory.resolve("http-output-limit.sse"),truncated);Files.writeString(directory.resolve("http-review-ac.sse"),ac);Files.writeString(directory.resolve("http-upstream-requests.json"),state.requests.toString());}
    }
    @Test void optionalEvidenceRoundAutoAppendsTrustedCounterexampleAndSumsUsage() throws Exception {
        int before=state.requests.size();
        String sse=call("POST","/ai/ask/stream","{\"problemId\":1,\"studentQuestion\":\"verify-counterexample\",\"studentCode\":\"student-current-code\",\"language\":\"cpp\"}");
        assertTrue(sse.contains("event:counterexample"),sse);assertTrue(sse.contains("event:diagnostic-status"));assertTrue(sse.contains("event:done"));
        assertEquals(before+2,state.requests.size());var first=state.requests.get(before);var last=state.requests.get(before+1);
        assertTrue(first.containsKey("tools"));assertFalse(last.containsKey("tools"));assertTrue(first.toString().contains("SELECTED-OFFICIAL-SOLUTION"));
        assertTrue(last.toString().contains("OFFICIAL_SAMPLE"));assertTrue(last.toString().contains("确认失败：true"));assertFalse(last.toString().contains("PRIVATE_ORACLE"));
        verify(state.usage,timeout(1000)).record(eq(7L),eq(1L),eq("ASK"),eq(24),eq(16),eq(false),anyLong(),eq("SUCCESS"),isNull(),any());
        String directory=System.getenv("OJ_TEST_OUTPUT_DIR");if(directory!=null){Files.writeString(Path.of(directory,"http-diagnostic-counterexample.sse"),sse);Files.writeString(Path.of(directory,"http-diagnostic-upstream.json"),new JSONArray(List.of(first,last)).toStringPretty());}
    }
    String call(String method,String path,String body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).header("X-Fixture-User","7");
        if(body==null)request.method(method,HttpRequest.BodyPublishers.noBody());else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString()).body();
    }
    static final class State implements AutoCloseable {
        final HttpServer upstream;
        final AiUsageRecorder usage=mock(AiUsageRecorder.class);
        final List<JSONObject> requests=new CopyOnWriteArrayList<>();
        final Map<String,List<JSONObject>> history=new ConcurrentHashMap<>();
        State()throws IOException {
            upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            upstream.createContext("/v1/chat/completions",exchange->{
                var request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);
                boolean verify=request.toString().contains("verify-counterexample") && request.containsKey("tools");
                if(verify) {
                    exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
                    String part1="data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"verify_1\",\"type\":\"function\",\"function\":{\"name\":\"verify_case\",\"arguments\":\"{\\\"input\\\":\"}}]}}]}\n\n";
                    String part2="data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"0\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8,\"total_tokens\":20}}\n\ndata: [DONE]\n\n";
                    exchange.getResponseBody().write((part1+part2).getBytes(StandardCharsets.UTF_8));exchange.close();return;
                }
                String reason=request.toString().contains("truncate")?"length":"stop";
                String text="参考实现：\\nint main() { return 0; }";
                var chunk=new JSONObject().set("choices",new JSONArray().put(new JSONObject().set("delta",new JSONObject().set("content",text)).set("finish_reason",reason)))
                        .set("usage",new JSONObject().set("prompt_tokens",12).set("completion_tokens",8).set("total_tokens",20));
                exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().write(("data: "+chunk+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));exchange.close();
            });upstream.start();
        }
        String endpoint(){return "http://127.0.0.1:"+upstream.getAddress().getPort()+"/v1/chat/completions";}
        public void close(){upstream.stop(0);}
    }
    record Actor(Long id) implements AuthenticatedUser{public Long getId(){return id;}}
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,SecurityAutoConfiguration.class,UserDetailsServiceAutoConfiguration.class})
    static class Fixture {
        @Bean(destroyMethod="close") State state()throws IOException{return new State();}
        @Bean FilterRegistrationBean<OncePerRequestFilter> actor(){return new FilterRegistrationBean<>(new OncePerRequestFilter(){
            protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new Actor(7L),null,List.of()));
                try{chain.doFilter(request,response);}finally{SecurityContextHolder.clearContext();}
            }
        });}
        @Bean GlobalExceptionHandler errors(){return new GlobalExceptionHandler();}
        @Bean(destroyMethod="shutdownNow") ExecutorService executor(){return Executors.newFixedThreadPool(2);}
        @Bean AiController controller(State state,ExecutorService executor){
            var runtime=mock(AiRuntimeConfigResolver.class);when(runtime.resolveCurrent()).thenReturn(AiServiceSnapshot.legacy(URI.create(state.endpoint()),"local-only","synthetic",false,512));
            var gateway=new AiGateway(List.of(new ChatCompletionsProvider(new ChatCompletionsClient(new AiEndpointGuard(state.endpoint())))),mock(AiResponseCache.class),runtime);
            var quota=mock(AiQuotaService.class);when(quota.tryAcquire(anyLong(),anyBoolean())).thenReturn(true);
            var sessions=mock(AiChatSessionService.class);
            when(sessions.loadHistory(anyLong(),anyLong())).thenAnswer(i->new ArrayList<>(state.history.getOrDefault(i.getArgument(0)+":"+i.getArgument(1),List.of())));
            doAnswer(i->{state.history.computeIfAbsent(i.getArgument(0)+":"+i.getArgument(1),k->new ArrayList<>()).addAll(List.of(TutorMessageBuilder.message("user",i.getArgument(2)),TutorMessageBuilder.message("assistant",i.getArgument(3))));return null;}).when(sessions).appendTurn(anyLong(),anyLong(),anyString(),anyString());
            when(sessions.conversation(anyLong(),anyLong())).thenAnswer(i->state.history.getOrDefault(i.getArgument(0)+":"+i.getArgument(1),List.of()));
            doAnswer(i->{state.history.remove(i.getArgument(0)+":"+i.getArgument(1));return null;}).when(sessions).clearConversation(anyLong(),anyLong());
            ProblemTutoringQuery problems=id->{if(id==999)throw new BusinessException(CommonErrorCode.ACCESS_DENIED);return new ProblemTutoringQuery.Context(id,"正式标题","服务端正式题面");};
            var submissions=mock(SubmissionReviewQuery.class);when(submissions.findById(anyLong())).thenAnswer(i->{Long id=i.getArgument(0);return Optional.of(new SubmissionReviewQuery.SubmissionReviewData(id,7L,1L,(byte)1,"cpp","int main(){}",100,1,1,null,id==9?9L:null,List.of()));});
            var builder=new TutorMessageBuilder(new PromptRegistry(mock(PromptTemplateMapper.class)));var contest=new AiContestGuard();
            var review=new ReviewApplicationService(gateway,quota,contest,submissions,problems,builder);
            var execution=mock(com.musealgo.oj.api.judge.CodeExecutionGateway.class);
            when(execution.reuse(any(),any(),any(),any(),any())).thenReturn(List.of());
            var run=new com.musealgo.oj.api.judge.CodeExecutionGateway.Observation("00000000-0000-0000-0000-000000000001","0","1","","",3,"0.01",10,false);
            when(execution.verify(any(),nullable(String.class),any())).thenReturn(new com.musealgo.oj.api.judge.CodeExecutionGateway.Verification(run,"0","OFFICIAL_SAMPLE",true,"实测输出不匹配"));
            var settings=mock(com.musealgo.oj.ai.diagnostics.DiagnosisSettings.class);when(settings.maxCases()).thenReturn(1);when(settings.waitSeconds()).thenReturn(2);
            var refs=mock(com.musealgo.oj.ai.reference.TutorReferenceService.class);when(refs.resolve(1L,true)).thenReturn(new com.musealgo.oj.ai.reference.TutorReferenceService.Reference(12L,"官方题解","SELECTED-OFFICIAL-SOLUTION",false,26));
            var diagnosis=new com.musealgo.oj.ai.diagnostics.TutorDiagnosisService(gateway,execution,settings,mock(com.musealgo.oj.ai.diagnostics.DiagnosticStats.class),refs,executor);
            var ai=new AiService(gateway,sessions,mock(RagContextService.class),quota,contest,mock(TeacherWorkbenchService.class),review,builder,diagnosis,state.usage);
            return new AiController(ai,quota,state.usage,sessions,executor,problems);
        }
    }
}
