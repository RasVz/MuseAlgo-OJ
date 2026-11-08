package com.musealgo.oj.community.post;

import com.musealgo.oj.community.entity.Post;
import com.musealgo.oj.community.service.PostService;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.api.teaching.ClassQuery;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostVisibilityTest {
    @Mock private PostService posts;
    @Mock private ClassQuery classes;
    @InjectMocks private PostTargetServiceImpl targets;
    private AutoCloseable mocks;
    @BeforeEach void setup() { mocks = org.mockito.MockitoAnnotations.openMocks(this); }

    @AfterEach void clear() throws Exception { SecurityContextHolder.clearContext(); mocks.close(); }

    private void actor(long id) {
        User user = new User(); user.setId(id);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private Post privatePost() {
        Post post = new Post(); post.setId(11L); post.setAuthorId(1L); post.setPrivacy(1); post.setType(0);
        when(posts.getById(11L)).thenReturn(post);
        return post;
    }

    @Test void privateCommentsRejectAnotherUser() {
        privatePost(); actor(2L);
        assertThrows(com.musealgo.oj.common.exception.BusinessException.class, () -> targets.requireCommentTarget(11L, 1));
    }

    @Test void privateCommentsRejectAnonymous() {
        privatePost();
        assertThrows(com.musealgo.oj.common.exception.BusinessException.class, () -> targets.requireCommentTarget(11L, 1));
    }

    @Test void privateCommentsRemainAvailableToAuthor() {
        Post post = privatePost(); actor(1L);
        assertSame(post, targets.requireCommentTarget(11L, 1));
    }

    @Test void publicCommentsRemainAvailableToAnonymous() {
        Post post = privatePost(); post.setPrivacy(0);
        assertSame(post, targets.requireCommentTarget(11L, 1));
    }

    private Post classPost() {
        Post post = privatePost(); post.setPrivacy(0); post.setClassId(20L); post.setAuthorId(99L);
        when(classes.findById(20L)).thenReturn(java.util.Optional.of(new ClassQuery.ClassInfo(20L, "Synthetic", 10L)));
        when(classes.findStudentIds(20L)).thenReturn(List.of(1L));
        return post;
    }
    @Test void classPostRejectsAnOutsiderEvenWhenPrivacyIsPublic() {
        classPost(); actor(2L);
        assertThrows(com.musealgo.oj.common.exception.BusinessException.class, () -> targets.requireReadable(11L, 2L));
    }
    @Test void classPostRejectsAnonymous() {
        classPost();
        assertThrows(com.musealgo.oj.common.exception.BusinessException.class, () -> targets.requireReadable(11L, null));
    }
    @Test void classMemberAndOwnerTeacherCanReadClassPost() {
        Post post = classPost(); actor(1L);
        assertSame(post, targets.requireReadable(11L, 1L));
        actor(10L); assertSame(post, targets.requireReadable(11L, 10L));
    }
    @Test void removedMemberLosesClassPostAccess() {
        classPost(); actor(1L); when(classes.findStudentIds(20L)).thenReturn(List.of());
        assertThrows(com.musealgo.oj.common.exception.BusinessException.class, () -> targets.requireReadable(11L, 1L));
    }
    @org.junit.jupiter.api.Test
    void removedClassMemberCannotEditTheirOwnOldClassPost() {
        var post = new com.musealgo.oj.community.entity.Post();
        post.setId(5L); post.setAuthorId(1L); post.setClassId(20L);
        when(posts.getById(5L)).thenReturn(post);
        when(classes.findById(20L)).thenReturn(java.util.Optional.of(new ClassQuery.ClassInfo(20L, "Synthetic", 10L)));
        when(classes.findStudentIds(20L)).thenReturn(java.util.List.of());
        org.junit.jupiter.api.Assertions.assertThrows(com.musealgo.oj.common.exception.ForbiddenException.class,
                () -> targets.requireEditable(5L, 1L));
    }
}
