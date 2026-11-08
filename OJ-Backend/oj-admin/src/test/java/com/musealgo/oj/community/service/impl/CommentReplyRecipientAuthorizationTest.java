package com.musealgo.oj.community.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import com.musealgo.oj.api.user.UserQuery;
import com.musealgo.oj.common.security.AuthenticatedUser;
import com.musealgo.oj.community.comment.CommentTargetDetails;
import com.musealgo.oj.community.comment.CommentTargetHandler;
import com.musealgo.oj.community.entity.Comment;
import com.musealgo.oj.community.entity.dto.CommentDTO;
import com.musealgo.oj.community.mapper.CommentMapper;
import com.musealgo.oj.community.notification.NotificationCommand;
import com.musealgo.oj.community.notification.NotificationTargetType;
import com.musealgo.oj.community.notification.NotificationType;
import com.musealgo.oj.community.service.LikeService;
import com.musealgo.oj.community.service.NotificationService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentReplyRecipientAuthorizationTest {

    @Mock private LikeService likeService;
    @Mock private UserQuery userQuery;
    @Mock private NotificationService notificationService;
    @Mock private CommentTargetHandler targetHandler;
    @Mock private CommentMapper commentMapper;

    private CommentServiceImpl service;

    @BeforeEach
    void setUp() {
        AuthenticatedUser principal = () -> 7L;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        when(targetHandler.supportedTypes()).thenReturn(Set.of(1));
        when(targetHandler.requireTarget(100L, 1)).thenReturn(
                new CommentTargetDetails(NotificationTargetType.POST, 50L));
        service = new CommentServiceImpl(likeService, userQuery, notificationService, List.of(targetHandler));
        ReflectionTestUtils.setField(service, "baseMapper", commentMapper);
        when(commentMapper.insert(any(Comment.class))).thenReturn(1);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void topLevelCommentCannotChooseReplyRecipient() {
        CommentDTO dto = new CommentDTO();
        dto.setEntityId(100L);
        dto.setEntityType(1);
        dto.setContent("arbitrary notification text");
        dto.setParentId(0L);
        dto.setReplyUserId(900L);
        // No replyCommentId: the attacker supplied an unrelated victim ID.

        service.publishComment(dto);

        ArgumentCaptor<Comment> commentCaptor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(commentCaptor.capture());
        assertEquals(7L, commentCaptor.getValue().getUserId());
        assertNull(commentCaptor.getValue().getReplyUserId());

        ArgumentCaptor<NotificationCommand> notificationCaptor = ArgumentCaptor.forClass(NotificationCommand.class);
        verify(notificationService).notify(notificationCaptor.capture());
        assertEquals(50L, notificationCaptor.getValue().recipientId());
        assertEquals(NotificationType.COMMENT, notificationCaptor.getValue().type());
    }

    @Test
    void replyWithoutReplyCommentIdUsesValidatedParentAuthor() {
        Comment parent = new Comment();
        parent.setId(11L);
        parent.setEntityId(100L);
        parent.setEntityType(1);
        parent.setUserId(88L);
        parent.setIsDeleted(0);
        when(commentMapper.selectById(11L)).thenReturn(parent);

        CommentDTO dto = new CommentDTO();
        dto.setEntityId(100L);
        dto.setEntityType(1);
        dto.setContent("reply");
        dto.setParentId(11L);
        dto.setReplyUserId(900L);

        service.publishComment(dto);

        ArgumentCaptor<Comment> commentCaptor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(commentCaptor.capture());
        assertEquals(88L, commentCaptor.getValue().getReplyUserId());
        ArgumentCaptor<NotificationCommand> notificationCaptor = ArgumentCaptor.forClass(NotificationCommand.class);
        verify(notificationService).notify(notificationCaptor.capture());
        assertEquals(88L, notificationCaptor.getValue().recipientId());
        assertEquals(NotificationType.REPLY, notificationCaptor.getValue().type());
    }
}
