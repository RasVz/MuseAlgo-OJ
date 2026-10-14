package top.everblue.oj.contest.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RankRedisServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void userRankUsesCompetitionRankingForTiedScores() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ZSetOperations<String, Object> zSet = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSet);
        String rankKey = "contest:rank:1";
        when(zSet.reverseRank(rankKey, "20")).thenReturn(2L);
        when(zSet.score(rankKey, "20")).thenReturn(80D);
        when(zSet.count(rankKey, Math.nextUp(80D), Double.POSITIVE_INFINITY)).thenReturn(1L);
        RankRedisService service = new RankRedisService(redisTemplate, mock(ContestService.class));

        Long rank = service.getUserRank(1L, 20L);

        assertEquals(2L, rank);
    }
}
