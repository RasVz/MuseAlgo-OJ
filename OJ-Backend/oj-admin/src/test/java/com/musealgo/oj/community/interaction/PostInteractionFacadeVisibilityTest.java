package com.musealgo.oj.community.interaction;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.community.error.CommunityErrorCode;
import com.musealgo.oj.community.post.PostTargetService;
import com.musealgo.oj.community.service.PostHotService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PostInteractionFacadeVisibilityTest {

    @Test
    void snapshotRejectsUnreadablePostWithoutReadingStats() {
        CommunityInteractionService interactions = mock(CommunityInteractionService.class);
        PostHotService hot = mock(PostHotService.class);
        PostTargetService targets = mock(PostTargetService.class);
        when(targets.requireReadable(9L, 2L))
                .thenThrow(new BusinessException(CommunityErrorCode.POST_NOT_FOUND, "文章不存在"));

        PostInteractionFacadeImpl facade = new PostInteractionFacadeImpl(interactions, hot, targets);

        assertThrows(BusinessException.class, () -> facade.snapshot(2L, 9L));
        verifyNoInteractions(hot, interactions);
    }
}
