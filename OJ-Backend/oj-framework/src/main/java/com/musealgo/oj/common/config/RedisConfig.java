package com.musealgo.oj.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisTemplate 序列化配置：Key 用 {@link StringRedisSerializer} 便于命令行排查，
 * Value 用带类名的 JSON 以便直接读写对象。
 * <p>
 * <strong>序列化方式不能随意更换。</strong>旧数据是旧序列化器写出的字节流，
 * 换了以后同样的 key 会读出乱码或反序列化异常（表现为取不到数据但不报错）；
 * 确需更换时先按新格式重写或清理相关 key。
 * </p>
 * <p>
 * Value 序列化器必须注册 {@link JavaTimeModule}，否则含 {@code LocalDate} 的画像写入即抛异常。
 * 本配置只设置 key 与 value，<strong>未设置 hashKey / hashValue</strong>：帖子计数等 Hash 结构
 * （见 {@link com.musealgo.oj.common.utils.PostRedisKey}）仍走框架默认的 JDK 序列化，
 * 字段名在 Redis 中不可读，改 key 序列化不会连带改变它。
 * </p>
 */
@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new RedisValueSerializer());
        return template;
    }

    /**
     * 在 Spring 默认的带类名序列化器上补充 Java 8 时间支持，避免自行重建默认类型规则。
     */
    private static final class RedisValueSerializer extends GenericJackson2JsonRedisSerializer {
        private RedisValueSerializer() {
            ObjectMapper mapper = getObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
    }
}
