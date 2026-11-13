package com.musealgo.oj.community.content;

import com.musealgo.oj.community.entity.Post;
import com.musealgo.oj.community.mapper.PostMapper;
import com.musealgo.oj.community.post.PostTargetService;
import com.musealgo.oj.community.service.CommunityTagService;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostEditOwnershipTest {
    @Test void editingTextCannotTransferOwnershipOrOverwriteCounters() {
        PostMapper mapper = mock(PostMapper.class);
        CommunityTagService tags = mock(CommunityTagService.class);
        PostTargetService targets = mock(PostTargetService.class);
        var service = new PostContentServiceImpl(mapper, null, null, tags, null, null, null, targets);
        when(tags.enabledNames()).thenReturn(Set.of());
        Post original = new Post(); original.setId(11L); original.setAuthorId(1L); original.setType(0);
        original.setLikes(3); original.setIsOfficial(0); original.setIsEssence(0); original.setIsTop(0);
        when(targets.requireEditable(11L, 1L)).thenReturn(original);
        when(mapper.updateById(any(Post.class))).thenReturn(1);
        Post edit = new Post(); edit.setId(11L); edit.setTitle("New title"); edit.setAuthorId(2L);
        edit.setContent("正文内容");
        edit.setLikes(999); edit.setIsDeleted(1); edit.setIsOfficial(1);
        assertTrue(service.update(edit, 1L));
        var saved = org.mockito.ArgumentCaptor.forClass(Post.class);
        verify(mapper).updateById(saved.capture());
        Post patch = saved.getValue();
        assertEquals("New title", patch.getTitle());
        assertTrue(patch.getAuthorId() == null || patch.getAuthorId().equals(1L));
        assertNull(patch.getLikes());
        assertNull(patch.getIsDeleted());
        assertEquals(0, patch.getIsOfficial());
    }
    @Test void publishingIgnoresClientCountersIdentityAndDeletionState() {
        PostMapper mapper = mock(PostMapper.class);
        CommunityTagService tags = mock(CommunityTagService.class);
        when(tags.enabledNames()).thenReturn(Set.of());
        var service = new PostContentServiceImpl(mapper, null, null, tags, null, null, null, null);
        when(mapper.insert(any(Post.class))).thenReturn(0);
        Post request = new Post(); request.setId(99L); request.setTitle("Synthetic new post");
        request.setContent("正文内容");
        request.setFavorites(999); request.setDislikes(999); request.setIsDeleted(1);
        request.setScore(999.0); request.setAuthorId(2L);
        service.publish(request, 1L);
        var saved = org.mockito.ArgumentCaptor.forClass(Post.class);
        verify(mapper).insert(saved.capture());
        assertNull(saved.getValue().getId());
        assertEquals(1L, saved.getValue().getAuthorId());
        assertEquals(0, saved.getValue().getFavorites());
        assertEquals(0, saved.getValue().getDislikes());
        assertEquals(0, saved.getValue().getIsDeleted());
        assertEquals(0.0, saved.getValue().getScore());
    }

}
