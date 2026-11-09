package com.musealgo.oj.ai;
import com.musealgo.oj.ai.usage.AiHintTraceRecorder;
import com.musealgo.oj.api.ai.HintTraceQuery;
import com.musealgo.oj.common.exception.BusinessException;
import org.springframework.data.redis.core.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class AiConversationSessionTest {
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") final ValueOperations<String,String> values=mock(ValueOperations.class);
    final AiChatSessionService sessions=new AiChatSessionService(redis,mock(AiHintTraceRecorder.class),mock(HintTraceQuery.class));
    @Test void busyConversationCannotBeClearedOrRestored(){when(redis.opsForValue()).thenReturn(values);when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(false);assertThrows(BusinessException.class,()->sessions.clearConversation(1L,2L));assertThrows(BusinessException.class,()->sessions.conversation(1L,2L));verify(redis,never()).delete(anyString());}
    @Test void clearDeletesOnlyAuthenticatedUsersProblemAfterLease(){when(redis.opsForValue()).thenReturn(values);when(values.setIfAbsent(eq("ai:conversation-lock:1:2"),anyString(),eq(Duration.ofMinutes(6)))).thenReturn(true);sessions.clearConversation(1L,2L);verify(redis).delete("ai:chat:1:2");verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),eq(java.util.List.of("ai:conversation-lock:1:2")),anyString());}
}
