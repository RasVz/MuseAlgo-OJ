package com.musealgo.oj.community.controller;

import com.musealgo.oj.common.security.AuthenticatedUser;
import com.musealgo.oj.community.entity.Post;
import com.musealgo.oj.community.service.PostService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAnnouncementControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void actor(long id) {
        AuthenticatedUser principal = () -> id;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    void newAnnouncementIsPublicByDefault() {
        PostService postService = mock(PostService.class);
        when(postService.save(any(Post.class))).thenReturn(true);
        actor(7L);

        Post post = new Post();
        post.setTitle("公告");
        post.setContent("内容");

        new AdminAnnouncementController(postService).save(post);

        assertEquals(Integer.valueOf(0), post.getPrivacy());
        assertEquals(Long.valueOf(7L), post.getAuthorId());
    }

    @Test
    void editingAnnouncementRestoresPublicVisibility() {
        PostService postService = mock(PostService.class);
        when(postService.updateById(any(Post.class))).thenReturn(true);

        Post post = new Post();
        post.setId(11L);
        post.setTitle("公告");
        post.setContent("内容");

        new AdminAnnouncementController(postService).save(post);

        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postService).updateById(captor.capture());
        assertEquals(Integer.valueOf(0), captor.getValue().getPrivacy());
    }
}
