package top.everblue.oj.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.everblue.oj.teaching.SlidevPathProvider;

/**
 * 把 Slidev 课件的构建产物目录挂到 HTTP 路径上，让课件能像静态资源一样被 iframe 直接嵌入。
 * <p>
 * 课件不是数据库里的附件，而是构建后落在工作区目录里的一堆 HTML/JS/CSS。
 * 本类只解决「怎么把这个目录暴露出去」，构建动作在 {@link SlidevBuilderService}，
 * 路径解析在 {@link SlidevPathProvider}——三者职责分开，避免路径规则被写死两份。
 * </p>
 * <p>
 * 同时注册 {@code /coursewares/**} 与 {@code /api/coursewares/**} 两个前缀是刻意的兼容处理：
 * 前端开发环境有 vite 代理，代理做了 rewrite 时走前者、不做 rewrite 时走后者的兜底。
 * <strong>不要觉得「重复注册」就删掉一个</strong>，删错的那一侧会直接导致课件 404 白屏。
 * </p>
 * <p>
 * 课件 URL 中的 uuid 目录名（{@code courseware.url} 字段）由构建时生成并入库，
 * 与磁盘目录一一对应且<strong>不可重命名</strong>：改名会让库里所有历史课件链接立刻失效，
 * 而前端拿到的 URL 没有任何规律可供重新推导。
 * </p>
 */
@Configuration
public class SlidevResourceConfig implements WebMvcConfigurer {

    private final String resourceLocation;

    public SlidevResourceConfig(SlidevPathProvider paths) {
        this.resourceLocation = paths.outputRoot().toUri().toString();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // /coursewares/**：本地 vite 代理 /api 重写后到达后端，或直接访问后端时使用；
        // /api/coursewares/**：代理不做重写时兜底，保证与前端 axios 的 /api 通道一致。
        registry.addResourceHandler("/coursewares/**", "/api/coursewares/**")
                .addResourceLocations(resourceLocation);
    }

}
