package top.everblue.oj.teaching;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Slidev 课件的路径与 URL 规则提供者——课件相关的路径口径只以本类为准。
 * <p>
 * 集中管理的原因是路径规则散落在三处就会出事故：构建时 {@code --base} 用的、数据库里
 * {@code courseware.url} 存的、以及浏览器实际请求的，必须<strong>三者完全一致</strong>，
 * 否则课件应用内部路由会按旧基准重写资源路径，表现为课件打开一片空白、只有 HTML 骨架。
 * {@link SlidevBuilderService} 负责构建、{@code SlidevResourceConfig} 负责暴露目录，
 * 规则只有这里定义。
 * </p>
 * <p>
 * 三个不可省略的设计决定：
 * </p>
 * <ol>
 *   <li><strong>构造时即创建输出目录。</strong> 课件构建依赖它存在，若等到构建时才创建，
 *       并发首次构建会撞在同一个 mkdir 上。</li>
 *   <li><strong>相对路径以「工作区根」为基准，而非 user.dir。</strong> 见 {@code findWorkspaceRoot}。</li>
 *   <li><strong>{@link #presentationUrl(String)} 默认返回同源相对地址</strong>，不写死主机名：
 *       换域名、换端口、换访问设备（localhost 调试 vs 局域网 IP）都不会失效，
 *       也不需要改数据库里的历史记录。</li>
 * </ol>
 * <p>
 * <strong>{@code findWorkspaceRoot()} 找不到源码工作区时兜底返回 {@code user.dir} 是必需的兜底，不是偷懒。</strong>
 * 生产镜像与 systemd 部署下不存在同时含 {@code OJ-backend} 与 {@code OJ-Slidev} 的目录树，
 * 若此时抛异常就是「应用启动即崩」——而课件功能不可用不该拖垮整个 OJ。
 * 兜底之后课件生成会因缺少 Slidev 工程而报错，其余功能照常运行。
 * 生产环境要启用课件功能，必须用 {@code OJ_SLIDEV_WORKER_DIR} / {@code OJ_SLIDEV_OUTPUT_DIR}
 * 指定绝对路径覆盖。
 * </p>
 * <p>
 * 另一个硬约束：<strong>课件 URL 里的 uuid 目录名不可重命名</strong>。
 * 它由构建时生成并写入数据库，与磁盘目录一一对应；改名会让所有历史课件链接立即失效，
 * 而 URL 里没有任何可供重新推导的规律。清理旧课件时也必须先确认数据库里没有引用。
 * </p>
 */
@Component
public class SlidevPathProvider {

    private final Path workerDir;
    private final Path outputRoot;
    private final String publicPath;
    private final String publicBaseUrl;

    public SlidevPathProvider(
            @Value("${oj.slidev.worker-dir:OJ-Slidev}") String workerDir,
            @Value("${oj.slidev.output-dir:OJ-Coursewares}") String outputDir,
            @Value("${oj.slidev.public-path:/coursewares/}") String publicPath,
            @Value("${oj.slidev.public-base-url:http://localhost:8080}") String publicBaseUrl
    ) {
        this.workerDir = resolvePath(workerDir);
        this.outputRoot = resolvePath(outputDir);
        this.publicPath = normalizePublicPath(publicPath);
        this.publicBaseUrl = normalizePublicBaseUrl(publicBaseUrl);

        try {
            Files.createDirectories(this.outputRoot);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建 Slidev 课件输出目录: " + this.outputRoot, e);
        }
    }

    public Path workerDir() {
        return workerDir;
    }

    public Path outputRoot() {
        return outputRoot;
    }

    public String publicPath() {
        return publicPath;
    }

    public String presentationUrl(String uuid) {
        // 默认返回同源相对地址（如 /coursewares/<uuid>/index.html）：
        // ① 不写死主机名，避免其他设备/域名访问时连接被拒；
        // ② 必须与 Slidev 构建的 --base（paths.publicPath() + uuid + "/"）完全一致，
        //    否则课件应用内部路由会按旧基准重写 URL 导致 404。
        // 本地由 vite 代理、线上由 nginx 转发；如需外部直接访问可配 OJ_SLIDEV_PUBLIC_BASE_URL。
        return publicBaseUrl + publicPath + uuid + "/index.html";
    }

    private static Path resolvePath(String configuredPath) {
        Path path = Paths.get(configuredPath);
        if (path.isAbsolute()) {
            return path.normalize().toAbsolutePath();
        }
        return findWorkspaceRoot().resolve(path).normalize().toAbsolutePath();
    }

    private static Path findWorkspaceRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("OJ-backend"))
                    && Files.isDirectory(candidate.resolve("OJ-Slidev"))) {
                return candidate;
            }
        }
        // 单 jar 部署（生产镜像 / systemd）不存在「源码工作区」这种目录树：
        // 退回当前工作目录，用 OJ_SLIDEV_WORKER_DIR / OJ_SLIDEV_OUTPUT_DIR 指定绝对路径覆盖。
        // 课件生成需要 OJ-Slidev 工程，缺失时相关功能报错，但不影响其余功能启动。
        return current;
    }

    private static String normalizePublicPath(String configuredPath) {
        String path = configuredPath.startsWith("/") ? configuredPath : "/" + configuredPath;
        return path.endsWith("/") ? path : path + "/";
    }

    private static String normalizePublicBaseUrl(String configuredUrl) {
        return configuredUrl.endsWith("/")
                ? configuredUrl.substring(0, configuredUrl.length() - 1)
                : configuredUrl;
    }
}
