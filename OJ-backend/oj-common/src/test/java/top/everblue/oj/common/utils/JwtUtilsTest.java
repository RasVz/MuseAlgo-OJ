package top.everblue.oj.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 验证依赖升级后认证契约保持不变，尤其是存量 HS256 token 与签名校验。 */
class JwtUtilsTest {

    private final String secret = UUID.randomUUID() + UUID.randomUUID().toString();
    private final SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    private final JwtUtils jwt = new JwtUtils(secret);

    @Test
    void accessTokenPreservesSubjectTypeLifetimeAndHs256() {
        String token = jwt.generateAccessToken("student");
        var parsed = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        Claims claims = parsed.getPayload();

        assertEquals("HS256", parsed.getHeader().getAlgorithm());
        assertEquals("student", jwt.extractUsername(token));
        assertEquals("access", claims.get("type"));
        assertEquals(Duration.ofHours(3).toMillis(),
                claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
        assertDoesNotThrow(() -> UUID.fromString(jwt.extractJti(token)));
        assertTrue(jwt.validateToken(token, "student"));
        assertFalse(jwt.validateToken(token, "someone-else"));
        assertTrue(jwt.getRemainingTtl(token) > Duration.ofHours(2).toMillis());
        assertNotEquals(jwt.extractJti(token), jwt.extractJti(jwt.generateAccessToken("student")));
    }

    @Test
    void refreshTokensKeepNormalAndRememberMeLifetimes() {
        assertRefreshLifetime(false, Duration.ofHours(6));
        assertRefreshLifetime(true, Duration.ofDays(7));
    }

    @Test
    void rejectsTokensSignedByAnotherKeyAndModifiedPayloads() {
        String token = jwt.generateAccessToken("student");
        JwtUtils otherIssuer = new JwtUtils(UUID.randomUUID() + UUID.randomUUID().toString());
        assertThrows(SignatureException.class, () -> otherIssuer.extractUsername(token));

        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        parts[1] = encode(payload.replace("student", "attacker").getBytes(StandardCharsets.UTF_8));
        String modified = String.join(".", parts);
        assertThrows(SignatureException.class, () -> jwt.extractUsername(modified));
    }

    @Test
    void rejectsExpiredTokens() {
        String token = Jwts.builder().subject("student")
                .expiration(new Date(0)).signWith(key, Jwts.SIG.HS256).compact();
        assertThrows(ExpiredJwtException.class, () -> jwt.extractUsername(token));
    }

    @Test
    void readsLegacyHs256TokensWithoutJti() throws Exception {
        // 用 JCA 独立构造旧版的 JWT 格式，避免签发与解析同时升级掩盖互操作问题。
        String header = encode("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encode(("{\"sub\":\"legacy-user\",\"exp\":4102444800,\"type\":\"access\"}")
                .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        String token = signingInput + "." + encode(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));

        assertEquals("legacy-user", jwt.extractUsername(token));
        assertNull(jwt.extractJti(token));
        assertTrue(jwt.validateToken(token, "legacy-user"));
    }

    @Test
    void failsFastForMissingOrWeakSecrets() {
        assertThrows(IllegalStateException.class, () -> new JwtUtils(null));
        assertThrows(IllegalStateException.class, () -> new JwtUtils(" "));
        assertThrows(IllegalStateException.class, () -> new JwtUtils("short"));
    }

    private void assertRefreshLifetime(boolean rememberMe, Duration lifetime) {
        String token = jwt.generateRefreshToken("student", rememberMe);
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        assertEquals("refresh", claims.get("type"));
        assertEquals(lifetime.toMillis(), claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
        assertDoesNotThrow(() -> UUID.fromString(jwt.extractJti(token)));
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
