package com.musealgo.oj.community.interaction;
import com.musealgo.oj.community.entity.*;
import com.musealgo.oj.community.service.*;
import com.musealgo.oj.community.post.PostTargetService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class PostFavoriteWriteTest {
 @org.junit.jupiter.api.BeforeAll static void metadata() {
  for(var type: new Class<?>[]{Post.class,UserFavorite.class}) com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(),"postfavorite"),type);
 }
 @Test void falseRelationInsertDoesNotClaimThePostIsFavorited() {
  var post=new Post();post.setId(1L);post.setType(0);
  var posts=mock(PostService.class);when(posts.getOne(any())).thenReturn(post);
  var targets=mock(PostTargetService.class);when(targets.requireReadable(eq(1L),any())).thenReturn(post);
  var handler=new PostInteractionHandler(posts,mock(UserFavoriteService.class),mock(PostHotService.class),mock(NotificationService.class),targets);
  assertThrows(IllegalStateException.class,()->handler.activate(new InteractionCommand(3L,InteractionTargetType.POST,1L,InteractionAction.FAVORITE)));
 }
 @Test void falseCounterUpdateMustAbortBeforeChangingRedisFavoriteState() {
  var post=new Post();post.setId(1L);post.setType(0);
  var posts=mock(PostService.class);when(posts.getOne(any())).thenReturn(post);
  var rows=mock(UserFavoriteService.class);when(rows.save(any())).thenReturn(true);
  var targets=mock(PostTargetService.class);when(targets.requireReadable(eq(1L),any())).thenReturn(post);
  var hot=mock(PostHotService.class);var handler=new PostInteractionHandler(posts,rows,hot,mock(NotificationService.class),targets);
  assertThrows(IllegalStateException.class,()->handler.activate(new InteractionCommand(3L,InteractionTargetType.POST,1L,InteractionAction.FAVORITE)));
  verify(hot,never()).favorite(any(),any(),any(),anyBoolean(),anyBoolean(),any());
 }
}
