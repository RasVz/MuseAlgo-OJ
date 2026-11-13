package com.musealgo.oj.judge.diagnostics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.api.judge.CodeExecutionGateway;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class RunReceiptStoreTest {
    @SuppressWarnings("unchecked") @Test void receiptsAreBoundToUserProblemCodeLanguageAndExpire(){
        var redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);
        Map<String,String> data=new HashMap<>();doAnswer(i->{data.put(i.getArgument(0),i.getArgument(1));return null;}).when(values).set(anyString(),anyString(),any(java.time.Duration.class));
        when(values.get(anyString())).thenAnswer(i->data.get(i.getArgument(0)));
        var store=new RunReceiptStore(redis,new ObjectMapper());String id="00000000-0000-0000-0000-000000000001";
        var run=new CodeExecutionGateway.Observation(id,"0","1","","",3,"0.1",100,false);store.save(7L,1L,"student","cpp",run);
        assertNotNull(store.read(7L,1L,"student","cpp",id));assertNull(store.read(8L,1L,"student","cpp",id));assertNull(store.read(7L,2L,"student","cpp",id));assertNull(store.read(7L,1L,"edited","cpp",id));assertNull(store.read(7L,1L,"student","java",id));
        assertFalse(data.values().iterator().next().contains("student"));verify(values).set(anyString(),anyString(),eq(java.time.Duration.ofMinutes(30)));
    }
}
