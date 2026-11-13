package com.musealgo.oj.common.config;

import com.musealgo.oj.common.filter.JwtAuthenticationTokenFilter;
import com.musealgo.oj.common.utils.JwtUtils;
import com.musealgo.oj.system.entity.*;
import com.musealgo.oj.system.service.JwtBlacklistService;
import com.musealgo.oj.system.service.impl.UserDetailsServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真正 Servlet + JWT 安全链：完成 SSE 的 ASYNC 分派不得再次拒绝已提交的响应。 */
@SpringBootTest(classes=AiSseSecurityHttpTest.Fixture.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.main.banner-mode=off","logging.level.root=WARN"})
@DirtiesContext
class AiSseSecurityHttpTest {
    @LocalServerPort int port;
    @Autowired JwtUtils jwt;
    @Autowired Probe controller;
    final HttpClient client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    HttpResponse<String> request(String path,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody());
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void authenticatedErrorStreamsHaveACompleteHttpBody() throws Exception {
        String token=jwt.generateAccessToken("sse-fixture",0L);
        for(String path:List.of("/ai/ask/stream","/ai/review")) {
            var response=request(path,token);assertEquals(200,response.statusCode());assertTrue(response.body().contains("event:app-error"));assertTrue(response.body().contains("AI_CONTEST_BLOCKED"));
        }
    }
    @Test void authenticatedSuccessfulStreamHasACompleteHttpBody() throws Exception {
        var response=request("/ai/ask/stream?done=true",jwt.generateAccessToken("sse-fixture",0L));assertEquals(200,response.statusCode());assertTrue(response.body().contains("event:done"));
    }
    @Test void originalRequestsRemainAuthenticated() throws Exception {
        int before=controller.calls.get();
        for(String path:List.of("/ai/ask/stream","/ai/review","/ai/ask/stream/other"))assertEquals(401,request(path,null).statusCode());
        assertEquals(401,request("/ai/review","invalid").statusCode());assertEquals(before,controller.calls.get());
    }
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=DataSourceAutoConfiguration.class)
    @Import({SecurityConfig.class,JwtAuthenticationTokenFilter.class})
    static class Fixture {
        @Bean JwtUtils jwt(){return new JwtUtils("synthetic-sse-http-regression-secret-20261110");}
        @Bean JwtBlacklistService blacklist(){return mock(JwtBlacklistService.class);}
        @Bean UserDetailsServiceImpl users(){
            var service=mock(UserDetailsServiceImpl.class);User user=new User();user.setId(7L);user.setUsername("sse-fixture");user.setCredentialVersion(0L);user.setRole((byte)1);
            when(service.loadUserByUsername("sse-fixture")).thenReturn(new LoginUser(user,List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));return service;
        }
        @Bean Probe probe(){return new Probe();}
    }
    @RestController static class Probe {
        final AtomicInteger calls=new AtomicInteger();
        @PostMapping(value={"/ai/ask/stream","/ai/review"},produces=MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream(@RequestParam(defaultValue="false") boolean done){
            calls.incrementAndGet();SseEmitter emitter=new SseEmitter(3000L);
            CompletableFuture.runAsync(()->{try{Thread.sleep(25);if(done)emitter.send(SseEmitter.event().name("done").data(""));else emitter.send(SseEmitter.event().name("app-error").data(Map.of("code",403,"errorCode","AI_CONTEST_BLOCKED"),MediaType.APPLICATION_JSON));emitter.complete();}catch(Exception failure){emitter.completeWithError(failure);}});
            return emitter;
        }
    }
}
