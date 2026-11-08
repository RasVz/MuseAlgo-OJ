package com.musealgo.oj.review;
import com.musealgo.oj.community.controller.PostHotController;
import com.musealgo.oj.community.entity.Post;
import com.musealgo.oj.community.entity.dto.PostQueryDTO;
import com.musealgo.oj.community.entity.vo.PostHotVO;
import com.musealgo.oj.community.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class PostHotColdSnapshotTest {
 @Test @SuppressWarnings("unchecked") void coldSnapshotSeedsDatabaseCountersButNeverOverwritesLiveStats() {
  var post=new Post();post.setId(1L);post.setType(0);post.setCategory("question");post.setPrivacy(0);post.setViews(20);post.setLikes(5);post.setCommentCount(4);post.setFavorites(3);
  var posts=mock(PostService.class);when(posts.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(post));when(posts.listByIds(List.of(1L))).thenReturn(List.of(post));
  var hot=mock(PostHotService.class);when(hot.getHotPosts("question",1,20)).thenReturn(List.of(1L));when(hot.getHotPostTotal("question")).thenReturn(1L);
  Map<String,Integer> cache=new HashMap<>();
  when(hot.getPostStats(1L)).thenAnswer(call->cache.isEmpty()?Map.of("views",0,"likes",0,"comments",0,"favorites",0):new HashMap<>(cache));
  doAnswer(call->{if(cache.isEmpty())cache.putAll((Map<String,Integer>)call.getArgument(1));return null;}).when(hot).initializeStatsIfAbsent(eq(1L),anyMap());
  var controller=new PostHotController(hot,posts,null);var query=new PostQueryDTO();query.setCategory("question");
  var cold=(PostHotVO)((List<?>)controller.getHotPosts(query).getData().get("list")).get(0);
  assertEquals(20,cold.getViews());assertEquals(5,cold.getLikes());assertEquals(4,cold.getComments());
  cache.put("likes",9);cache.put("views",99);
  var warm=(PostHotVO)((List<?>)controller.getHotPosts(query).getData().get("list")).get(0);
  assertEquals(9,warm.getLikes());assertEquals(99,warm.getViews());
 }
}
