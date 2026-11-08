package com.musealgo.oj.judge.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BackupFreshnessChecker} 单测（OPS-02）：只依赖临时目录，不连数据库、不启动 Spring。
 *
 * <p>覆盖「未配置备份监控不得显示健康」的备份侧语义，以及新鲜度/损坏/清单不完整/IO 失败。</p>
 */
class BackupFreshnessCheckerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Path root;
    private Path backupDir;
    private BackupFreshnessChecker checker;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("ops02-backup-root");
        backupDir = Files.createDirectory(root.resolve("2026-10-21T04-00-00"));
        checker = new BackupFreshnessChecker();
        ReflectionTestUtils.setField(checker, "root", root.toString());
        ReflectionTestUtils.setField(checker, "maxAgeHours", 26L);
        ReflectionTestUtils.setField(checker, "verifyChecksums", true);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响断言
                }
            });
        }
    }

    @Test
    void unconfiguredRootIsNotHealthy() {
        ReflectionTestUtils.setField(checker, "root", "");
        BackupStatus status = checker.check();
        assertFalse(status.configured());
        assertFalse(status.healthy());
        assertEquals(-1L, status.ageSeconds());
        assertFalse(status.corrupt());
        assertTrue(status.lastCheckOk());
    }

    @Test
    void emptyRootWithoutManifestIsConfiguredButNotHealthy() {
        BackupStatus status = checker.check();
        assertTrue(status.configured());
        assertFalse(status.healthy());
        assertEquals(-1L, status.ageSeconds());
        assertFalse(status.corrupt());
    }

    @Test
    void freshValidBackupIsHealthy() throws IOException {
        writeBackup(backupDir, Instant.now().minus(1, ChronoUnit.HOURS), true);
        BackupStatus status = checker.check();
        assertTrue(status.configured());
        assertTrue(status.healthy());
        assertTrue(status.ageSeconds() >= 3500 && status.ageSeconds() <= 3700, "age=" + status.ageSeconds());
        assertFalse(status.corrupt());
    }

    @Test
    void staleBackupIsConfiguredButNotHealthy() throws IOException {
        writeBackup(backupDir, Instant.now().minus(27, ChronoUnit.HOURS), true);
        BackupStatus status = checker.check();
        assertTrue(status.configured());
        assertFalse(status.healthy());
        assertTrue(status.ageSeconds() > 26 * 3600L);
    }

    @Test
    void checksumMismatchIsCorrupt() throws IOException {
        writeBackup(backupDir, Instant.now(), true);
        Files.writeString(backupDir.resolve("database.sql"), "tampered", StandardCharsets.UTF_8);
        BackupStatus status = checker.check();
        assertTrue(status.configured());
        assertTrue(status.corrupt());
        assertFalse(status.healthy());
    }

    @Test
    void missingFourthFileIsCorrupt() throws IOException {
        writeBackup(backupDir, Instant.now(), true);
        Files.delete(backupDir.resolve("coursewares.tar.gz"));
        BackupStatus status = checker.check();
        assertTrue(status.corrupt());
        assertFalse(status.healthy());
    }

    @Test
    void incompleteManifestIsCorrupt() throws IOException {
        writeBackup(backupDir, Instant.now(), true);
        ObjectNode manifest = (ObjectNode) objectMapper.readTree(backupDir.resolve("manifest.json").toFile());
        // 文件清单在 manifest 的 files 子对象里，不是根节点键
        ((ObjectNode) manifest.get("files")).remove("coursewares.tar.gz");
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(backupDir.resolve("manifest.json").toFile(), manifest);
        assertTrue(checker.check().corrupt());
    }

    @Test
    void unsupportedFormatIsCorrupt() throws IOException {
        writeBackup(backupDir, Instant.now(), true);
        ObjectNode manifest = (ObjectNode) objectMapper.readTree(backupDir.resolve("manifest.json").toFile());
        manifest.put("format", 2);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(backupDir.resolve("manifest.json").toFile(), manifest);
        BackupStatus status = checker.check();
        assertTrue(status.corrupt());
        assertFalse(status.healthy());
    }

    @Test
    void checksumVerificationCanBeDisabledForLargeBackups() throws IOException {
        writeBackup(backupDir, Instant.now(), true);
        Files.writeString(backupDir.resolve("database.sql"), "tampered", StandardCharsets.UTF_8);
        ReflectionTestUtils.setField(checker, "verifyChecksums", false);
        BackupStatus status = checker.check();
        assertFalse(status.corrupt());
        assertTrue(status.healthy());
    }

    @Test
    void rootPathThatIsNotDirectoryIsTreatedAsMissing() throws IOException {
        Path file = Files.writeString(root.resolve("not-a-dir"), "x");
        ReflectionTestUtils.setField(checker, "root", file.toString());
        BackupStatus status = checker.check();
        assertTrue(status.configured());
        assertFalse(status.healthy());
        assertFalse(status.corrupt());
    }

    /** 在备份目录写入与 ops.py 一致的 manifest + 四件文件；corruptChecksum 控制校验和是否正确。 */
    private void writeBackup(Path dir, Instant createdAt, boolean correctChecksums) throws IOException {
        Files.writeString(dir.resolve("database.sql"), "SELECT 1;", StandardCharsets.UTF_8);
        Files.write(dir.resolve("testcases.tar.gz"), new byte[]{1, 2, 3});
        Files.write(dir.resolve("uploads.tar.gz"), new byte[]{4, 5, 6});
        Files.write(dir.resolve("coursewares.tar.gz"), new byte[]{7, 8, 9});
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("format", 1);
        manifest.put("created_at", createdAt.truncatedTo(ChronoUnit.SECONDS).toString());
        ObjectNode files = manifest.putObject("files");
        // 注意：不能用 Map.of(...) —— 它不接受 null 值，构造即抛 NPE（gate 报的 7 个 writeBackup NPE 根因）。
        Map<String, String> digests = new HashMap<>();
        digests.put("database.sql", sha256Text("SELECT 1;"));
        digests.put("testcases.tar.gz", sha256(dir.resolve("testcases.tar.gz")));
        digests.put("uploads.tar.gz", sha256(dir.resolve("uploads.tar.gz")));
        digests.put("coursewares.tar.gz", sha256(dir.resolve("coursewares.tar.gz")));
        for (Map.Entry<String, String> entry : digests.entrySet()) {
            files.put(entry.getKey(), correctChecksums ? entry.getValue() : "0".repeat(64));
        }
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("manifest.json").toFile(), manifest);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(Files.readAllBytes(path)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Text(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
