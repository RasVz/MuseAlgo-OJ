package com.musealgo.oj.review;
import com.musealgo.oj.community.controller.PostHotController;
import com.musealgo.oj.community.interaction.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class PostHotFailureTest {
 @Test void emptyLaterPageRetainsTheRankingsTotal() {
  var hot=mock(com.musealgo.oj.community.service.PostHotService.class);
  var posts=mock(com.musealgo.oj.community.service.PostService.class);
  when(hot.getHotPostTotal(null)).thenReturn(7L);
  var query=new com.musealgo.oj.community.entity.dto.PostQueryDTO();query.setPage(2);
  assertEquals(7L,new PostHotController(hot,posts,null).getHotPosts(query).getData().get("total"));
 }
 @Test void internalInteractionFailureIsNotReportedAsBusinessConflictOrLeaked() {
  var facade=mock(PostInteractionFacade.class);var failure=new IllegalStateException("synthetic SQL path secret");
  when(facade.apply(any(),any(),any(),anyBoolean())).thenThrow(failure);
  var controller=new PostHotController(null,null,facade);var user=new com.musealgo.oj.system.entity.User();user.setId(3L);
  org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user,null,List.of()));
  try {assertSame(failure,assertThrows(IllegalStateException.class,()->controller.likePost(1L,Map.of("isLike",true))));
   assertSame(failure,assertThrows(IllegalStateException.class,()->controller.favoritePost(1L,Map.of("isFavorite",true))));
  }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
 }
}
