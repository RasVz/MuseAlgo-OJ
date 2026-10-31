package com.musealgo.oj.ai.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiKeyCipherTest {

    @TempDir
    Path tempDir;

    @Test
    void encryptsWithRandomNonceAndDecryptsWithoutLeakingPlaintext() throws Exception {
        Path keyFile = writeKey("master-a");
        AiKeyCipher cipher = new AiKeyCipher(keyFile.toString());

        String first = cipher.encrypt("sk-test-secret");
        String second = cipher.encrypt("sk-test-secret");

        assertTrue(first.startsWith("v1:"));
        assertFalse(first.contains("sk-test-secret"));
        assertNotEquals(first, second, "同一明文必须因随机 nonce 得到不同密文");
        assertEquals("sk-test-secret", cipher.decrypt(first, "v1"));
        assertEquals("sk-test-secret", cipher.decrypt(second, "v1"));
    }

    @Test
    void tamperedCiphertextAndWrongKeyFailSafely() throws Exception {
        AiKeyCipher cipher = new AiKeyCipher(writeKey("master-a").toString());
        String envelope = cipher.encrypt("sk-test-secret");
        char last = envelope.charAt(envelope.length() - 1);
        String tampered = envelope.substring(0, envelope.length() - 1) + (last == 'A' ? 'B' : 'A');

        AiKeyException tamperError = assertThrows(AiKeyException.class,
                () -> cipher.decrypt(tampered, "v1"));
        assertEquals("AI 服务密钥解密失败", tamperError.getMessage());

        AiKeyCipher wrongKey = new AiKeyCipher(writeKey("master-b").toString());
        assertThrows(AiKeyException.class, () -> wrongKey.decrypt(envelope, "v1"));
    }

    @Test
    void missingOrMalformedMasterKeyNeverFallsBackToPlaintext() throws Exception {
        AiKeyCipher missing = new AiKeyCipher("");
        assertFalse(missing.configured());
        assertThrows(AiKeyException.class, () -> missing.encrypt("sk-test-secret"));

        Path tooShort = tempDir.resolve("short.key");
        Files.writeString(tooShort, Base64.getEncoder().encodeToString(new byte[16]), StandardCharsets.US_ASCII);
        AiKeyCipher malformed = new AiKeyCipher(tooShort.toString());
        assertThrows(AiKeyException.class, () -> malformed.encrypt("sk-test-secret"));
    }

    @Test
    void rejectsUnknownEnvelopeVersionAndInvalidFormat() throws Exception {
        AiKeyCipher cipher = new AiKeyCipher(writeKey("master-a").toString());
        AiKeyException version = assertThrows(AiKeyException.class,
                () -> cipher.decrypt("v1:nonce:cipher", "v2"));
        assertEquals("AI 服务密钥版本不受支持", version.getMessage());
        assertThrows(AiKeyException.class, () -> cipher.decrypt("garbage", "v1"));
        assertThrows(AiKeyException.class, () -> cipher.decrypt("v1:not-base64:cipher", "v1"));
    }

    private Path writeKey(String label) throws Exception {
        byte[] key = new byte[32];
        new SecureRandom(label.getBytes(StandardCharsets.UTF_8)).nextBytes(key);
        Path file = tempDir.resolve(label + ".key");
        Files.writeString(file, Base64.getEncoder().encodeToString(key), StandardCharsets.US_ASCII);
        return file;
    }
}
