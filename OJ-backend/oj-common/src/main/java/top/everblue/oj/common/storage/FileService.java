package top.everblue.oj.common.storage;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件上传服务：用户图片走 COS，服务端生成的文档走字节流接口，两条出口共用同一套落盘/命名逻辑。
 * <p>
 * <strong>COS 未配置时自动降级到本地磁盘</strong>，写入 {@code file.upload-dir} 并返回
 * {@code /uploads/**} 地址，保证本地开发与私有化部署「零配置能跑起来」。降级是有意为之而非
 * 临时兜底，所以校验逻辑在两条路径之前统一执行，不会出现「本地能传、COS 才被拒」的行为差异。
 * </p>
 * <p>
 * 图片上传必须过四重校验（非空 → 大小 → 扩展名白名单 → Content-Type + 魔数），
 * 其中魔数校验是防「改后缀伪装」的<b>唯一有效手段</b>：扩展名与 Content-Type 都由客户端提供，
 * 只有文件头字节不可伪造。不要因为「白名单已经够用」而删掉魔数校验。
 * </p>
 * <p>
 * 落盘文件名一律用 UUID 重命名而非原始文件名：既防同名覆盖，也避免把用户上传的原始文件名
 * 泄露到存储路径里造成的信息泄露与非法字符问题。
 * </p>
 * <p>
 * COS 客户端按调用创建、用完即 {@code shutdown()}，没有做成单例复用。这是已知取舍——
 * 图片上传是低频操作，连接复用收益有限，而单例需要额外处理凭据轮换与线程安全，复杂度不划算。
 * 若将来图片上传成为高频接口，这里是需要重新设计的点。
 * </p>
 */
@Slf4j
@Service
public class FileService {

    /** 图片上传白名单扩展名（含点前缀写法之外，这里用无点小写）。 */
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    /** 扩展名 → 文件头魔数（Magic Number）校验器，防"改后缀绕过白名单"。 */
    private static final Map<String, byte[][]> IMAGE_MAGIC_BY_EXT = Map.of(
            "jpg", new byte[][]{{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}},
            "jpeg", new byte[][]{{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}},
            "png", new byte[][]{{(byte) 0x89, 'P', 'N', 'G'}},
            "gif", new byte[][]{{'G', 'I', 'F', '8'}},
            "webp", new byte[][]{{'R', 'I', 'F', 'F'}},
            "bmp", new byte[][]{{'B', 'M'}}
    );

    @Value("${tencent.cos.secret-id}")
    private String secretId;

    @Value("${tencent.cos.secret-key}")
    private String secretKey;

    @Value("${tencent.cos.region}")
    private String regionName;

    @Value("${tencent.cos.bucket-name}")
    private String bucketName;

    @Value("${tencent.cos.base-url}")
    private String baseUrl;

    // 本地降级存储：COS 未配置时图片/文件保存到本机并通过 /uploads/** 对外访问
    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    @Value("${file.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    @Value("${file.max-upload-mb:5}")
    private long maxUploadMb;

    /**
     * 上传文件到腾讯云 COS
     * @param file 前端传来的文件
     * @return 返回上传成功后的图片 URL
     */
    public String uploadToCos(MultipartFile file) {
        validateImageFile(file);
        if (!isCosConfigured()) {
            return uploadToLocal(file, "post-images");
        }

        COSClient cosClient = buildClient();

        try {
            // 获取原始文件名并生成唯一的新文件名，防止同名文件覆盖
            String originalFilename = file.getOriginalFilename();
            String ext = originalFilename.substring(originalFilename.lastIndexOf("."));
            String newFileName = "post-images/" + UUID.randomUUID().toString().replace("-", "") + ext;

            // 创建临时文件用于上传
            File localFile = File.createTempFile("temp", null);
            file.transferTo(localFile);

            // 4. 执行上传请求
            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, newFileName, localFile);
            cosClient.putObject(putObjectRequest);

            // 5. 删除临时文件并关闭客户端
            localFile.delete();
            cosClient.shutdown();

            // 6. 返回可供公网访问的图片完整 URL
            return baseUrl + "/" + newFileName;

        } catch (IOException e) {
            log.error("文件上传失败", e);
            throw new RuntimeException("文件上传失败");
        }
    }

    /**
     * 上传字节流到 COS（供服务端生成的文件使用，如家长报告 PDF）。
     *
     * @param bytes 文件内容
     * @param dir   目录前缀，如 "weekly-reports"
     * @param ext   扩展名（含点），如 ".pdf"
     * @return 公网可访问的完整 URL
     */
    public String uploadBytes(byte[] bytes, String dir, String ext) {
        if (!isCosConfigured()) {
            return uploadBytesToLocal(bytes, dir, ext);
        }

        String newFileName = dir + "/" + UUID.randomUUID().toString().replace("-", "") + ext;

        File localFile = null;
        COSClient cosClient = buildClient();
        try {
            localFile = File.createTempFile("upload", ext);
            Files.write(localFile.toPath(), bytes);
            cosClient.putObject(new PutObjectRequest(bucketName, newFileName, localFile));
            return baseUrl + "/" + newFileName;
        } catch (IOException e) {
            log.error("字节流上传失败 dir={}", dir, e);
            throw new RuntimeException("文件上传失败");
        } finally {
            cosClient.shutdown();
            if (localFile != null && localFile.exists() && !localFile.delete()) {
                log.warn("临时文件删除失败: {}", localFile.getAbsolutePath());
            }
        }
    }

    private String uploadToLocal(MultipartFile file, String dir) {
        try {
            String originalFilename = file.getOriginalFilename();
            String ext = "";
            if (originalFilename != null) {
                int dot = originalFilename.lastIndexOf(".");
                if (dot >= 0) {
                    ext = originalFilename.substring(dot);
                    if (ext.length() > 12) ext = "";
                }
            }
            String relativePath = dir + "/" + UUID.randomUUID().toString().replace("-", "") + ext;
            Path target = Path.of(uploadDir, relativePath).toAbsolutePath();
            Files.createDirectories(target.getParent());
            file.transferTo(target);
            log.info("图片已保存到本地: {}", target);
            return publicBaseUrl + "/uploads/" + relativePath;
        } catch (IOException e) {
            log.error("本地文件上传失败", e);
            throw new RuntimeException("文件上传失败");
        }
    }

    private String uploadBytesToLocal(byte[] bytes, String dir, String ext) {
        try {
            String relativePath = dir + "/" + UUID.randomUUID().toString().replace("-", "") + ext;
            Path target = Path.of(uploadDir, relativePath).toAbsolutePath();
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return publicBaseUrl + "/uploads/" + relativePath;
        } catch (IOException e) {
            log.error("本地文件写入失败 dir={}", dir, e);
            throw new RuntimeException("文件上传失败");
        }
    }

    /**
     * COS 是否已配置（未配置时调用方应跳过上传统一降级，而不是抛异常）。
     */
    public boolean isCosConfigured() {
        return secretId != null && !secretId.isBlank()
                && bucketName != null && !bucketName.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }

    private COSClient buildClient() {
        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
        Region region = new Region(regionName);
        ClientConfig clientConfig = new ClientConfig(region);
        return new COSClient(cred, clientConfig);
    }

    /**
     * 图片上传四重校验：非空 → 大小 → 扩展名白名单 → 内容类型/魔数。
     * <p>
     * 魔数校验可防「把 exe 改名成 .png」之类的伪装绕过；不匹配一律拒绝。
     * 校验失败抛 {@link IllegalArgumentException}，由全局异常处理器映射为 400。
     * </p>
     *
     * @param file 待校验文件
     */
    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        long maxBytes = maxUploadMb * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("文件大小不能超过 " + maxUploadMb + "MB");
        }

        String filename = file.getOriginalFilename();
        String ext = filename == null ? "" : filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("仅支持上传 jpg/jpeg/png/gif/webp/bmp 格式的图片");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new IllegalArgumentException("文件内容类型不合法，仅允许图片");
        }

        byte[] header;
        try {
            header = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("文件读取失败，请重试");
        }
        if (!matchesMagic(header, ext)) {
            throw new IllegalArgumentException("文件内容与扩展名不符，已拒绝上传");
        }
    }

    /**
     * 校验文件头是否匹配该扩展名的魔数。
     *
     * @param header 文件内容（取前若干字节即可）
     * @param ext    小写扩展名（已在白名单内）
     * @return true 表示魔数匹配
     */
    private boolean matchesMagic(byte[] header, String ext) {
        if (header == null) {
            return false;
        }
        // webp 特殊：RIFF + 4 字节长度 + WEBP，需至少 12 字节
        if ("webp".equals(ext)) {
            return header.length >= 12
                    && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
        }
        byte[][] candidates = IMAGE_MAGIC_BY_EXT.get(ext);
        if (candidates == null) {
            return false;
        }
        for (byte[] magic : candidates) {
            if (header.length >= magic.length && startsWith(header, magic)) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
