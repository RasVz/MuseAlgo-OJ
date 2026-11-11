package com.musealgo.oj.ai.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 服务商 API Key 的认证加密。
 *
 * <p>主密钥是独立的 32 字节随机值，只从运维挂载的只读文件读取，每次使用时重新读取，
 * 以便受控轮换；数据库只保存 {@code v1:nonce:ciphertext}。刻意不支持环境变量直传，
 * 也不复用 JWT 密钥。主密钥缺失或解密失败时抛出统一异常，调用方必须安全失败。</p>
 */
@Component
public class AiKeyCipher {

    private static final String ENVELOPE_VERSION = "v1";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD = "oj-ai-service-key:v1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_KEY_FILE_BYTES = 4096;

    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();

    public AiKeyCipher(@Value("${oj.ai.crypto.key-file:}") String keyFilePath) {
        this.keyFile = keyFilePath == null || keyFilePath.isBlank()
                ? null
                : Path.of(keyFilePath.trim());
    }

    /** 返回带版本前缀的密文；同一明文每次产生不同 nonce。 */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new AiKeyException("API 密钥不能为空");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, loadKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ENVELOPE_VERSION + ":" + encode(nonce) + ":" + encode(encrypted);
        } catch (GeneralSecurityException e) {
            throw AiKeyException.master("AI 主密钥不可用，无法保存服务密钥");
        }
    }

    /** 解密并校验认证标签；版本不匹配、密文损坏或密钥错误都返回同一个安全错误。 */
    public String decrypt(String envelope, String encryptionVersion) {
        if (!ENVELOPE_VERSION.equals(encryptionVersion)) {
            throw new AiKeyException("AI 服务密钥版本不受支持");
        }
        if (envelope == null || envelope.isBlank()) {
            throw new AiKeyException("AI 服务密钥未配置");
        }
        String[] parts = envelope.split(":", -1);
        if (parts.length != 3 || !ENVELOPE_VERSION.equals(parts[0])) {
            throw new AiKeyException("AI 服务密钥密文格式无效");
        }
        byte[] nonce;
        byte[] encrypted;
        try {
            nonce = decode(parts[1]);
            encrypted = decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new AiKeyException("AI 服务密钥密文格式无效");
        }
        if (nonce.length != NONCE_BYTES || encrypted.length < TAG_BITS / 8) {
            throw new AiKeyException("AI 服务密钥密文格式无效");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, loadKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new AiKeyException("AI 服务密钥解密失败");
        } catch (GeneralSecurityException e) {
            throw AiKeyException.master("AI 主密钥不可用，无法读取服务密钥");
        }
    }

    public boolean configured() {
        return keyFile != null;
    }

    /** File readiness is separate from the ability to decrypt any existing service. */
    public record Readiness(boolean ready, String message) { }

    public Readiness readiness() {
        try {
            loadKey();
            return new Readiness(true, "主密钥已就绪");
        } catch (AiKeyException e) {
            return new Readiness(false, e.getMessage());
        }
    }

    private SecretKeySpec loadKey() {
        if (keyFile == null) {
            throw AiKeyException.master("AI 主密钥未配置");
        }
        try {
            if (!Files.isRegularFile(keyFile)) {
                throw AiKeyException.master("AI 主密钥文件不存在");
            }
            long size = Files.size(keyFile);
            if (size <= 0 || size > MAX_KEY_FILE_BYTES) {
                throw AiKeyException.master("AI 主密钥文件大小无效");
            }
            String encoded = Files.readString(keyFile, StandardCharsets.US_ASCII).trim();
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != KEY_BYTES) {
                throw AiKeyException.master("AI 主密钥必须为 32 字节 Base64");
            }
            return new SecretKeySpec(key, "AES");
        } catch (IOException e) {
            throw AiKeyException.master("AI 主密钥文件不可读");
        } catch (IllegalArgumentException e) {
            throw AiKeyException.master("AI 主密钥不是合法 Base64");
        }
    }

    private static String encode(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }
}
