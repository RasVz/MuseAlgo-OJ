package com.musealgo.oj.judge.controller;
import com.musealgo.oj.judge.entity.UserCodeDraft;
import com.musealgo.oj.judge.service.UserCodeDraftService;
import com.musealgo.oj.common.security.AuthenticatedUser;
import com.musealgo.oj.common.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class LatestDraftControllerTest {
    record Actor(Long id) implements AuthenticatedUser { public Long getId(){return id;} }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void latestDraftRequiresAuthentication(){var service=mock(UserCodeDraftService.class);assertThrows(BusinessException.class,()->new CodeDraftController(service).latestDraft(1L));verifyNoInteractions(service);}
    @Test void latestDraftReadsCurrentActorAndReturnsActualLanguage(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new Actor(7L),null,List.of()));var service=mock(UserCodeDraftService.class);var draft=new UserCodeDraft();draft.setLanguage("java");draft.setCode("class Main {}");when(service.getLatestDraft(7L,1L)).thenReturn(draft);var result=new CodeDraftController(service).latestDraft(1L);assertEquals("java",result.getData().language());assertEquals("class Main {}",result.getData().code());}
}
