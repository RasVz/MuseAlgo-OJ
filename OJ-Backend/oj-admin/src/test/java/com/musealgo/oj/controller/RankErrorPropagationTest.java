package com.musealgo.oj.controller;

import com.musealgo.oj.contest.controller.RankController;
import com.musealgo.oj.contest.service.RankService;
import com.musealgo.oj.system.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RankErrorPropagationTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void anonymousSidebarStillHasNoRank() {
        var service=mock(RankService.class);var result=new RankController(service).getMyRank();
        assertEquals(200,result.getCode());assertEquals(-1L,result.getData());verifyNoInteractions(service);
    }
    @Test void databaseFailureMustNotLookLikeAnUnrankedUser() {
        User user=new User();user.setId(7L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
        var service=mock(RankService.class);var failure=new DataAccessResourceFailureException("synthetic DB failure");
        when(service.getMyRank(7L)).thenThrow(failure);
        assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->new RankController(service).getMyRank()));
    }
}
