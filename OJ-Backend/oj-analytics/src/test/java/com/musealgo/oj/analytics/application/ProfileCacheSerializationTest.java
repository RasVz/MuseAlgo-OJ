package com.musealgo.oj.analytics.application;

import com.musealgo.oj.analytics.domain.StudentProfile;
import com.musealgo.oj.common.config.RedisConfig;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ProfileCacheSerializationTest {

    @Test
    @SuppressWarnings("unchecked")
    void valueSerializerRoundTripsProfileWithDates() {
        RedisTemplate<String, Object> template = new RedisConfig().redisTemplate(mock(RedisConnectionFactory.class));
        RedisSerializer<Object> serializer = (RedisSerializer<Object>) template.getValueSerializer();
        StudentProfile profile = new StudentProfile(11L, "student", null, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30),
                null, List.of(), Map.of(), null, List.of());

        byte[] bytes = serializer.serialize(profile);
        String json = new String(bytes, StandardCharsets.UTF_8);

        assertTrue(json.contains("\"@class\":\"com.musealgo.oj.analytics.domain.StudentProfile\""), json);
        assertTrue(json.contains("2026-10-01"), json);
        StudentProfile restored = (StudentProfile) serializer.deserialize(bytes);
        assertEquals(profile.periodStart(), restored.periodStart());
        assertEquals(profile.periodEnd(), restored.periodEnd());
        assertEquals(profile.studentName(), restored.studentName());
    }
}
