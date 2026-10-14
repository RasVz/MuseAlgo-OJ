package top.everblue.oj.system.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.storage.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 通用文件上传接口。
 * <p>
 * <strong>本控制器只负责 HTTP 适配，所有校验与存储决策都在
 * {@link top.everblue.oj.common.storage.FileService}。</strong> 这里的空值检查只是避免
 * 无意义的调用，真正的四重校验（大小、扩展名白名单、Content-Type、魔数）在 FileService 内部。
 * <strong>不要在这里再加校验规则</strong>，否则会出现「部分调用方校验、部分不校验」的不一致，
 * 而且 FileService 降级到本地存储这条路径会绕过 Controller 层。
 * </p>
 * <p>
 * COS 未配置时接口<strong>依然可用</strong>（降级为本地存储并返回 {@code /uploads/**} 地址），
 * 因此不能因为「生产用 COS」就把本地返回的 URL 当成异常处理掉。
 * </p>
 * <p>
 * 接口路径是 {@code /file/upload}，与 SecurityConfig 中放行的 {@code /uploads/**} 静态路径不同：
 * 后者是上传完成后的<strong>访问</strong>入口，两者不要混淆，也不要因为放行了访问路径就顺带放行本上传接口。
 * </p>
 * <p>
 * 上传属于高成本接口，目前没有 {@code @RateLimit}；若遭遇滥用，优先在存储层加配额，
 * 而不是简单加 IP 限流——同一教室的学生往往共用出口 IP。
 * </p>
 */
@Tag(name = "文件管理", description = "文件上传与下载")
@RestController
@RequiredArgsConstructor
@RequestMapping("/file")
public class FileController {

    private final FileService fileService;

    /**
     * 通用图片上传接口
     */
    @Operation(summary = "上传文件到 COS")
    @PostMapping("/upload")
    public Result<String> uploadImage(@RequestParam("file") MultipartFile file) {
        // 校验文件是否为空
        if (file.isEmpty()) {
            return Result.fail("上传文件不能为空");
        }

        // 调用 COS 服务上传，并拿到图片的 URL
        String imageUrl = fileService.uploadToCos(file);

        // 将 URL 返回给前端
        return Result.success(imageUrl);
    }
}
