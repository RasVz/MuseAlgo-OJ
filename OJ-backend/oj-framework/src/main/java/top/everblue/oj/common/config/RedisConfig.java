package top.everblue.oj.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisTemplate 序列化配置。
 * <p>
 * Key 用 {@link StringRedisSerializer}：保证 Redis 里 key 可读，运维可以直接在命令行里
 * 排查问题；Value 用 {@link GenericJackson2JsonRedisSerializer}，写入对象时自动带上类名，
 * 读出时无需手工转型。
 * </p>
 * <p>
 * <strong>序列化方式不能随便改。</strong> Redis 里的旧数据是用旧序列化器写进去的字节流，
 * 换序列化器后同样的 key 读出来是乱码或反序列化异常（表现为取不到数据但不报错）。
 * 确实要改时，必须先把线上数据按新格式重写一遍或直接清空相关 key。
 * </p>
 * <p>
 * 注意本配置只设置了 key 与 value，<strong>未显式设置 hashKey / hashValue</strong>：
 * 帖子计数等 Hash 结构（见 {@link top.everblue.oj.common.utils.PostRedisKey}）走的是框架默认的
 * JDK 序列化，字段名在 Redis 里不可读。因此「改 key 序列化」与「改 Hash 字段序列化」是两件独立的事，
 * 别默认前者会带上后者。
 * </p>
 */
@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        // Key 使用 String 序列化
        template.setKeySerializer(new StringRedisSerializer());
        // Value 使用 JSON 序列化 (或者也用 String，看你习惯)
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}