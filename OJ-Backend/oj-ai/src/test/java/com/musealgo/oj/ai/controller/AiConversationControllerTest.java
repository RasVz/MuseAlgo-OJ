package com.musealgo.oj.ai.controller;
import com.musealgo.oj.ai.*;
import com.musealgo.oj.ai.quota.AiQuotaService;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import com.musealgo.oj.api.problem.ProblemTutoringQuery;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.security.AuthenticatedUser;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AiConversationControllerTest {
    final AiChatSessionService sessions=mock(AiChatSessionService.class);
    final ProblemTutoringQuery problems=mock(ProblemTutoringQuery.class);
    final AiController controller=new AiController(mock(AiService.class),mock(AiQuotaService.class),mock(AiUsageRecorder.class),sessions,mock(ExecutorService.class),problems);
    record Actor(Long id) implements AuthenticatedUser { public Long getId(){return id;} }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void login(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new Actor(7L),null,List.of()));}
    @Test void conversationUsesAuthenticatedUserAndChecksProblemAccess(){login();when(sessions.conversation(7L,10L)).thenReturn(List.of());assertEquals(200,controller.conversation(10L).getCode());verify(problems).load(10L);verify(sessions).conversation(7L,10L);}
    @Test void clearChecksPermissionBeforeDeleting(){login();doThrow(new BusinessException(com.musealgo.oj.common.exception.CommonErrorCode.ACCESS_DENIED)).when(problems).load(10L);assertThrows(BusinessException.class,()->controller.clearConversation(10L));verifyNoInteractions(sessions);}
    @Test void clearOnlyCurrentUsersConversation(){login();controller.clearConversation(10L);verify(sessions).clearConversation(7L,10L);}
    @Test void anonymousCannotReadOrClear(){assertThrows(BusinessException.class,()->controller.conversation(10L));assertThrows(BusinessException.class,()->controller.clearConversation(10L));verifyNoInteractions(sessions,problems);}
}
