package top.everblue.oj.teaching;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Slidev 课件构建服务：把上传的 Markdown 交给 Node 侧的 slidev CLI 编译成静态站点。
 * <p>
 * 构建以<strong>外部进程</strong>方式调用 Node 生态的 slidev，而不是引入 JVM 内的 Markdown 渲染器：
 * Slidev 的能力（代码高亮、演示者模式、自定义布局）在 Node 侧是完整的，自己在 JVM 里重实现既不现实也没必要。
 * 代价是部署环境必须具备 Node.js 与 {@code OJ-Slidev} 工程（含 node_modules），
 * 这一点由 {@link SlidevPathProvider} 的工作区兜底逻辑处理。
 * </p>
 * <p>
 * <strong>{@code --base} 参数必须与 {@link SlidevPathProvider#presentationUrl(String)} 完全一致</strong>
 * （都是 {@code publicPath + uuid + "/"}）。两者不一致时构建本身会成功、页面也能打开，
 * 但课件内部的 JS/CSS/图片请求会 404，表现为白屏——这个 bug 只在浏览器里出现，日志完全正常，
 * 因此两处路径必须始终一起改。
 * </p>
 * <p>
 * 每份课件用随机 uuid 作为<strong>独立目录</strong>，不复用目录名：目录名一旦可预测，
 * 上传者就能通过猜测路径覆盖他人的课件。uuid 同时也是写入 {@code courseware.url} 的部分，
 * <strong>不可重命名</strong>，改名即断链。
 * </p>
 * <p>
 * 构建日志逐行打到 info 是有意的：Slidev 编译失败的原因（如 frontmatter 语法错误）
 * 只体现在 stdout 里，不打出来的话用户只能看到一句「编译失败」，无从排查。
 * 但也意味着高并发构建会产生大量日志，评估日志量时要算上这部分。
 * </p>
 * <p>
 * 注意本服务<strong>没有超时控制</strong>：slidev 进程卡住会一直占着线程。
 * 目前靠 slidev 自身在 {@code CI=true} 下快速失败来兜底。若引入超大课件导致构建变慢，
 * 应当补上带超时的等待，不要让它无限期占用线程池。
 * </p>
 */
@Slf4j
@Service
public class SlidevBuilderService {

    private final SlidevPathProvider paths;

    public SlidevBuilderService(SlidevPathProvider paths) {
        this.paths = paths;
    }

    public String buildPresentation(MultipartFile mdFile) throws Exception {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        Files.createDirectories(paths.workerDir());

        Path tempMd = paths.workerDir().resolve(uuid + ".md");
        Path outputDir = paths.outputRoot().resolve(uuid);

        try {
            mdFile.transferTo(tempMd);

            List<String> command = List.of(
                    paths.workerDir().resolve("node_modules/.bin/slidev").toString(),
                    "build",
                    tempMd.getFileName().toString(),
                    "--out", outputDir.toString(),
                    "--base", paths.publicPath() + uuid + "/"
            );
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(paths.workerDir().toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.environment().put("CI", "true");

            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[Slidev build] {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Slidev 编译失败，请检查 Markdown 语法或本地 Node.js 环境。退出码: " + exitCode);
            }
        } finally {
            Files.deleteIfExists(tempMd);
        }

        return paths.presentationUrl(uuid);
    }
}
