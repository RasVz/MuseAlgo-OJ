package com.musealgo.oj.common.utils;

import com.musealgo.oj.community.entity.Post;
import com.musealgo.oj.community.mapper.PostMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostHotSyncJobTest {
    @Test
    void coldCacheUsesPersistedCountersForTheFirstScore() {
        exercise(Map.of(), Map.of("views", 42, "likes", 7, "dislikes", 1, "favorites", 3, "comments", 2));
    }

    @Test
    void warmCacheRemainsTheAuthoritativeCounterSnapshot() {
        exercise(Map.of("views", "100", "likes", "9"),
                Map.of("views", 100, "likes", 9, "dislikes", 0, "favorites", 0, "comments", 0));
    }

    @SuppressWarnings("unchecked")
    private void exercise(Map<Object, Object> snapshot, Map<String, Integer> expected) {
        var redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        ZSetOperations<String, String> ranks = mock(ZSetOperations.class);
        var mapper = mock(PostMapper.class);
        var calculator = mock(HotScoreCalculator.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForZSet()).thenReturn(ranks);
        when(hashes.entries(PostRedisKey.stat(1L))).thenReturn(snapshot);
        var post = new Post();
        post.setId(1L); post.setViews(42); post.setLikes(7); post.setDislikes(1);
        post.setFavorites(3); post.setCommentCount(2); post.setIsTop(0); post.setIsEssence(0);
        post.setCreateTime(LocalDateTime.of(2026, 11, 1, 0, 0)); post.setCategory("algorithm");
        when(mapper.selectRecentActivePosts(any(), eq(0), eq(500))).thenReturn(List.of(post));
        when(calculator.calculate(anyMap(), eq(false), eq(false), eq(post.getCreateTime()))).thenReturn(12.5);

        new PostHotSyncJob(redis, calculator, mapper).syncHotScore();

        verify(calculator).calculate(expected, false, false, post.getCreateTime());
        verify(ranks).add(PostRedisKey.hotGlobal(), "1", 12.5);
        verify(mapper).updateHotScore(eq(1L), eq(12.5), any());
        if (!snapshot.isEmpty()) verify(hashes, never()).putAll(anyString(), anyMap());
    }
}
