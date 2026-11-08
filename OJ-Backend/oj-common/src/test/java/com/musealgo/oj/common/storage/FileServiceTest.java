package com.musealgo.oj.common.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传类型/大小校验单测：覆盖白名单扩展名、Content-Type、魔数、大小四重校验。
 */
class FileServiceTest {

    private FileService fileService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        fileService = new FileService();
        ReflectionTestUtils.setField(fileService, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(fileService, "publicBaseUrl", "http://test");
        ReflectionTestUtils.setField(fileService, "maxUploadMb", 1L);
        // COS 未配置 → 走本地存储，便于断言
        ReflectionTestUtils.setField(fileService, "secretId", "");
        ReflectionTestUtils.setField(fileService, "bucketName", "");
        ReflectionTestUtils.setField(fileService, "baseUrl", "");
    }

    private static byte[] pngBytes() {
        // 合法 PNG 头：89 50 4E 47 0D 0A 1A 0A
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    }

    @Test
    void validPngUploadsAndSavesLocally() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", pngBytes());

        String url = fileService.uploadToCos(file);

        assertTrue(url.startsWith("http://test/uploads/post-images/"));
        assertTrue(url.endsWith(".png"));
    }

    @Test
    void disguisedFileWithWrongMagicRejected() {
        // 扩展名 .png、Content-Type image/png，但内容只是文本 —— 魔数不匹配
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.png", "image/png", "not an image at all".getBytes(StandardCharsets.UTF_8));

        var ex = assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                () -> fileService.uploadToCos(file));
        assertTrue(ex.getMessage().contains("内容与扩展名不符"));
    }

    @Test
    void oversizedFileRejected() {
        byte[] big = new byte[2 * 1024 * 1024]; // 2MB > 1MB 上限
        big[0] = (byte) 0x89;
        big[1] = 'P';
        big[2] = 'N';
        big[3] = 'G';
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);

        var ex = assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                () -> fileService.uploadToCos(file));
        assertTrue(ex.getMessage().contains("大小不能超过"));
    }

    @Test
    void nonAllowedExtensionRejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "virus.exe", "application/octet-stream", pngBytes());

        var ex = assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                () -> fileService.uploadToCos(file));
        assertTrue(ex.getMessage().contains("格式"));
    }

    @Test
    void nonImageContentTypeRejected() {
        // 魔数正确但 Content-Type 不是 image/*
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "application/octet-stream", pngBytes());

        var ex = assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                () -> fileService.uploadToCos(file));
        assertTrue(ex.getMessage().contains("内容类型不合法"));
    }

    @Test
    void webpMagicValidated() {
        byte[] webp = new byte[12];
        webp[0] = 'R'; webp[1] = 'I'; webp[2] = 'F'; webp[3] = 'F';
        webp[4] = 0; webp[5] = 0; webp[6] = 0; webp[7] = 0;
        webp[8] = 'W'; webp[9] = 'E'; webp[10] = 'B'; webp[11] = 'P';

        MockMultipartFile file = new MockMultipartFile("file", "a.webp", "image/webp", webp);
        String url = fileService.uploadToCos(file);
        assertTrue(url.endsWith(".webp"));

        // 缺 WEBP 头的伪 webp 拒绝
        webp[8] = 'X';
        MockMultipartFile fake = new MockMultipartFile("file", "b.webp", "image/webp", webp);
        var ex = assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                () -> fileService.uploadToCos(fake));
        assertTrue(ex.getMessage().contains("内容与扩展名不符"));
    }

    @Test
    void emptyFileRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);
        var ex = assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                () -> fileService.uploadToCos(file));
        assertTrue(ex.getMessage().contains("不能为空"));
    }

    @Test
    void uploadedFileActuallyPersisted() throws java.io.IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "real.png", "image/png", pngBytes());
        String url = fileService.uploadToCos(file);
        String relative = url.substring("http://test/uploads/".length());
        Path saved = tempDir.resolve(relative);
        assertEquals(11, java.nio.file.Files.readAllBytes(saved).length);
    }
    @Test
    void failedCosImageUploadClosesClientAndDeletesTemporaryFile() throws Exception {
        ReflectionTestUtils.setField(fileService, "secretId", "synthetic-id");
        ReflectionTestUtils.setField(fileService, "secretKey", "synthetic-key");
        ReflectionTestUtils.setField(fileService, "bucketName", "synthetic-bucket");
        ReflectionTestUtils.setField(fileService, "baseUrl", "https://synthetic.invalid");
        ReflectionTestUtils.setField(fileService, "regionName", "ap-test");
        var temporary = new java.util.concurrent.atomic.AtomicReference<java.io.File>();
        var failure = new com.qcloud.cos.exception.CosClientException("synthetic upstream failure");
        try (var clients = org.mockito.Mockito.mockConstruction(com.qcloud.cos.COSClient.class, (client, context) -> {
            org.mockito.Mockito.doAnswer(call -> {
                var request = call.getArgument(0, com.qcloud.cos.model.PutObjectRequest.class);
                temporary.set(request.getFile());
                assertTrue(request.getFile().isFile());
                throw failure;
            }).when(client).putObject(org.mockito.ArgumentMatchers.any(com.qcloud.cos.model.PutObjectRequest.class));
        })) {
            assertThrows(RuntimeException.class, () -> fileService.uploadToCos(
                    new MockMultipartFile("file", "avatar.png", "image/png", pngBytes())));
            org.mockito.Mockito.verify(clients.constructed().get(0)).shutdown();
            assertTrue(temporary.get() != null && !temporary.get().exists(), "failed upload must remove temporary file");
        } finally {
            if (temporary.get() != null) java.nio.file.Files.deleteIfExists(temporary.get().toPath());
        }
    }

    @Test
    void localWriteFailurePreservesItsIoCause() throws Exception {
        Path blocked = tempDir.resolve("not-a-directory");
        java.nio.file.Files.writeString(blocked, "synthetic");
        ReflectionTestUtils.setField(fileService, "uploadDir", blocked.toString());
        var failure = assertThrows(RuntimeException.class, () -> fileService.uploadToCos(
                new MockMultipartFile("file", "avatar.png", "image/png", pngBytes())));
        assertTrue(failure.getCause() instanceof java.io.IOException);
    }

}
