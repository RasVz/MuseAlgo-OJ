package top.everblue.oj.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * 本地文件上传的静态访问映射：COS 未配置时图片降级保存到本地，
 * 通过 /uploads/** 对外提供访问。
 */
@Configuration
public class UploadResourceConfig implements WebMvcConfigurer {

    private final String resourceLocation;

    public UploadResourceConfig(@Value("${file.upload-dir:uploads}") String uploadDir) {
        String location = Path.of(uploadDir).toAbsolutePath().toUri().toString();
        // 目录首次启动时可能不存在，Path.toUri() 此时不会补上资源解析所需的末尾斜杠。
        this.resourceLocation = location.endsWith("/") ? location : location + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(resourceLocation);
    }
}
