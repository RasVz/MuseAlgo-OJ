package top.everblue.oj.teaching;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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
 * 构建受大小、并发和超时限制；输出先写临时日志，避免输出管道阻塞超时等待。
 * </p>
 */
@Slf4j
@Service
public class SlidevBuilderService {

    private final SlidevPathProvider paths;

    public SlidevBuilderService(SlidevPathProvider paths) {
        this.paths = paths;
    }

    private final java.util.concurrent.Semaphore buildSlots = new java.util.concurrent.Semaphore(2);

    @org.springframework.beans.factory.annotation.Value("${oj.slidev.build-timeout-seconds:120}")
    private long buildTimeoutSeconds = 120;

    public String buildPresentation(MultipartFile mdFile) throws Exception {
        top.everblue.oj.common.utils.SecurityUtils.requireTeacherOrAdmin();
        if (mdFile.isEmpty() || mdFile.getSize() > 5L * 1024 * 1024)
            throw new IllegalArgumentException("Markdown课件须为非空且不超过5MB");
        if (!buildSlots.tryAcquire()) throw new IllegalStateException("课件构建繁忙，请稍后重试");
        Path tempMd = null;
        Path buildLog = null;
        Process process = null;
        try {
            String uuid = UUID.randomUUID().toString().replace("-", "");
            Files.createDirectories(paths.workerDir());
            tempMd = paths.workerDir().resolve(uuid + ".md");
            buildLog = Files.createTempFile("oj-slidev-", ".log");
            mdFile.transferTo(tempMd);
            List<String> command = List.of(paths.workerDir().resolve("node_modules/.bin/slidev").toString(),
                    "build", tempMd.getFileName().toString(), "--out", paths.outputRoot().resolve(uuid).toString(),
                    "--base", paths.publicPath() + uuid + "/");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(paths.workerDir().toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(buildLog.toFile());
            builder.environment().put("CI", "true");
            process = builder.start();
            if (!process.waitFor(Math.max(1, buildTimeoutSeconds), java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("课件构建超时");
            if (process.exitValue() != 0) {
                try (var lines = Files.lines(buildLog)) { lines.limit(200).forEach(line -> log.warn("[Slidev build] {}", line)); }
                throw new IllegalStateException("Slidev编译失败，请检查课件内容");
            }
            return paths.presentationUrl(uuid);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        } finally {
            try {
                if (process != null) {
                    try {
                        process.descendants().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
                    } catch (RuntimeException ex) {
                        log.warn("无法枚举课件构建子进程，将终止主进程", ex);
                    } finally {
                        if (process.isAlive()) {
                            process.destroyForcibly();
                            if (!Thread.currentThread().isInterrupted()) process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                        }
                    }
                }
            } finally {
                try {
                    if (tempMd != null) Files.deleteIfExists(tempMd);
                    if (buildLog != null) Files.deleteIfExists(buildLog);
                } finally { buildSlots.release(); }
            }
        }
    }
}
