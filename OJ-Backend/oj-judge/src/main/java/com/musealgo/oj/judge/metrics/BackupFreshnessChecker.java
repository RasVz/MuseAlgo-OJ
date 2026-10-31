package com.musealgo.oj.judge.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

/**
 * 备份新鲜度/损坏检查（OPS-02，进程内版本，口径对齐 {@code scripts/ops.py check}）。
 *
 * <p>扫描 {@code oj.monitoring.backup.root} 下每个子目录的 {@code manifest.json}，取修改时间最新的一份，
 * 校验清单格式（format=1、恰好四件文件、created_at 可解析）与（可选）sha256，然后按
 * {@code max-age-hours}（默认 26，与 ops.py check 的 --max-backup-age-hours 一致）判断新鲜度。</p>
 *
 * <p>分工：Java 侧只做「新鲜度 + 清单完整性 + 可选 sha256」，tar 成员路径穿越等强校验仍以
 * {@code python3 scripts/ops.py verify --source <备份目录>} 为准，不维护两套归档校验逻辑。</p>
 *
 * <p>安全：日志只输出目录名/状态/秒数，不输出清单内容、文件正文或用户数据。</p>
 */
@Slf4j
@Component
public class BackupFreshnessChecker {

    /** 与 ops.py 的 VOLUMES 对齐：备份目录必须恰好包含的四件产物。 */
    static final Set<String> EXPECTED_FILES = Set.of(
            "database.sql", "testcases.tar.gz", "uploads.tar.gz", "coursewares.tar.gz");

    private static final String MANIFEST = "manifest.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${oj.monitoring.backup.root:}")
    private String root;

    @Value("${oj.monitoring.backup.max-age-hours:26}")
    private long maxAgeHours;

    @Value("${oj.monitoring.backup.verify-checksums:true}")
    private boolean verifyChecksums;

    /**
     * 执行一次备份状态检查；本方法不抛异常，所有失败都映射为 {@link BackupStatus}。
     */
    public BackupStatus check() {
        String configuredRoot = root == null ? "" : root.trim();
        if (configuredRoot.isEmpty()) {
            // 未配置：不是「健康」，也不是「检查失败」，而是「没监控」——由 configured 区分。
            return BackupStatus.notConfigured();
        }
        Path rootPath;
        try {
            rootPath = Path.of(configuredRoot);
        } catch (RuntimeException e) {
            log.warn("【监控】备份根目录配置非法（oj.monitoring.backup.root）: {}", e.getMessage());
            return BackupStatus.checkFailed();
        }
        if (!Files.isDirectory(rootPath)) {
            log.info("【监控】备份根目录不存在或不是目录，视为无备份");
            return BackupStatus.missing();
        }
        try {
            Path latest = findLatestManifestDir(rootPath);
            if (latest == null) {
                log.info("【监控】备份目录未发现 manifest.json");
                return BackupStatus.missing();
            }
            Instant createdAt = verifyLatestBackup(latest);
            long ageSeconds = Math.max(0L, Instant.now().getEpochSecond() - createdAt.getEpochSecond());
            boolean healthy = ageSeconds <= maxAgeHours * 3600L;
            if (!healthy) {
                log.warn("【监控】最新备份超过新鲜度阈值 dir={}, ageSeconds={}, maxAgeHours={}",
                        latest.getFileName(), ageSeconds, maxAgeHours);
            }
            return BackupStatus.ok(ageSeconds, healthy);
        } catch (IOException e) {
            log.warn("【监控】备份状态检查失败（IO/权限）: {}", e.getMessage());
            return BackupStatus.checkFailed();
        } catch (BackupCorruptException e) {
            log.warn("【监控】最新备份损坏: {}", e.getMessage());
            return BackupStatus.corrupted();
        }
    }

    /** 取 manifest.json 修改时间最新的备份子目录；无候选返回 null。 */
    private Path findLatestManifestDir(Path rootPath) throws IOException {
        Path latest = null;
        long latestMillis = Long.MIN_VALUE;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootPath)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                Path manifest = child.resolve(MANIFEST);
                if (!Files.isRegularFile(manifest)) {
                    continue;
                }
                long millis = Files.getLastModifiedTime(manifest).toMillis();
                if (millis > latestMillis) {
                    latestMillis = millis;
                    latest = child;
                }
            }
        }
        return latest;
    }

    /** 校验最新备份；通过返回清单 created_at，失败抛 {@link BackupCorruptException}。 */
    private Instant verifyLatestBackup(Path dir) throws IOException {
        String text = Files.readString(dir.resolve(MANIFEST));
        JsonNode manifest;
        try {
            manifest = objectMapper.readTree(text);
        } catch (IOException e) {
            throw new BackupCorruptException("manifest 不是合法 JSON");
        }
        if (manifest == null || !manifest.isObject() || manifest.path("format").asInt(-1) != 1) {
            throw new BackupCorruptException("manifest format 不受支持");
        }
        JsonNode files = manifest.get("files");
        if (files == null || !files.isObject()) {
            throw new BackupCorruptException("manifest 缺少 files 清单");
        }
        Set<String> names = new HashSet<>();
        files.fieldNames().forEachRemaining(names::add);
        if (!names.equals(EXPECTED_FILES)) {
            throw new BackupCorruptException("manifest 文件清单不完整（实际 " + names.size() + " 项，期望 4 项）");
        }
        Instant createdAt = parseCreatedAt(manifest.get("created_at"));
        if (verifyChecksums) {
            for (String name : EXPECTED_FILES) {
                Path file = dir.resolve(name);
                if (!Files.isRegularFile(file)) {
                    throw new BackupCorruptException("备份缺少文件: " + name);
                }
                String expected = files.path(name).asText("");
                if (!expected.matches("[0-9a-f]{64}")) {
                    throw new BackupCorruptException("备份额外校验和格式非法: " + name);
                }
                if (!expected.equals(sha256(file))) {
                    throw new BackupCorruptException("备份校验和不匹配: " + name);
                }
            }
        }
        return createdAt;
    }

    private Instant parseCreatedAt(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw new BackupCorruptException("manifest 缺少 created_at");
        }
        String value = node.asText();
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException e) {
                throw new BackupCorruptException("manifest created_at 无法解析");
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    /** 清单/文件损坏（区别于 IO 检查失败）。 */
    static final class BackupCorruptException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BackupCorruptException(String message) {
            super(message);
        }
    }
}
