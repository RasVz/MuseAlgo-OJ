package top.everblue.oj.common.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 全局时间格式：LocalDateTime 统一序列化为 yyyy-MM-dd HH:mm:ss，
 * 反序列化兼容空格与 ISO 'T' 两种写法（管理端/接口时间显示不再出现 T）。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter LENIENT = DateTimeFormatter.ofPattern("yyyy-MM-dd['T'][ ]HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeCustomizer() {
        return builder -> {
            builder.serializers(new LocalDateTimeSerializer(DISPLAY));
            builder.deserializers(new LenientLocalDateTimeDeserializer());
        };
    }

    /** 宽松反序列化：接受 2026-08-01T19:43:19 与 2026-08-01 19:43:19 */
    static class LenientLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public Class<?> handledType() {
            return LocalDateTime.class;
        }

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return LocalDateTime.parse(text.trim(), LENIENT);
            } catch (DateTimeParseException e) {
                try {
                    return LocalDateTime.parse(text.trim());
                } catch (DateTimeParseException e2) {
                    throw new IOException("无法解析时间: " + text, e);
                }
            }
        }
    }
}
