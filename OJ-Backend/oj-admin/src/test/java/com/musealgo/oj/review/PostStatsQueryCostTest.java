package com.musealgo.oj.review;
import com.musealgo.oj.community.service.PostRankingService;
import com.musealgo.oj.community.mapper.PostMapper;
import com.musealgo.oj.common.utils.HotScoreCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
class PostStatsQueryCostTest {
 @Test @SuppressWarnings("unchecked") void cachedRankingStatsDoNotIssueOneDatabaseQueryPerPost() {
  var redis=mock(StringRedisTemplate.class);HashOperations<String,Object,Object> hashes=mock(HashOperations.class);when(redis.opsForHash()).thenReturn(hashes);
  var mapper=mock(PostMapper.class);var service=new PostRankingService(redis,new HotScoreCalculator());ReflectionTestUtils.setField(service,"posts",mapper);
  service.getPostStats(1L);verifyNoInteractions(mapper);
 }
}
