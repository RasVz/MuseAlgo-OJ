package top.everblue.oj.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 签发与解析工具，无状态认证链路的唯一入口。
 * <p>
 * 同时签发两类 token：{@code type=access} 用于接口鉴权（3 小时），{@code type=refresh} 仅用于换取新
 * access token（6 小时 / 勾选「记住我」7 天）。把有效期拉开是为了让前端不用在高频操作中反复静默续签，
 * 而不是放宽鉴权窗口——access token 一旦过期必须走 refresh 重新签发。
 * </p>
 * <p>
 * 注销能力依赖 payload 里的 {@code jti}：服务端把它放进黑名单并按剩余有效期设 TTL。存量 token
 * （注销功能上线前签发）没有这个字段，{@link #extractJti(String)} 返回 {@code null}，
 * 调用方按「未注销」处理——这是刻意的向后兼容，若改成抛异常或直接拒绝，会让升级期间所有老用户被踢下线。
 * </p>
 * <p>
 * 类本身不做任何 token 存储，Redis 黑名单的读写在调用方；这里只保证「签出来的 token 能被验回原样」。
 * </p>
 */
@Component
public class JwtUtils {

    /** HS256 要求密钥 ≥ 256 bit（32 字节），低于此值 JwtBuilder 会抛 WeakKeyException */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;

    /**
     * 密钥来自配置项 jwt.secret（环境变量 JWT_SECRET 注入），禁止硬编码默认值。
     * 启动时 fail-fast：未配置或长度不足直接抛异常，防止用弱密钥上线。
     */
    public JwtUtils(@Value("${jwt.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("jwt.secret 未配置：请设置 JWT_SECRET 环境变量（≥32 字节随机串，可用 openssl rand -base64 48 生成）");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("jwt.secret 长度不足：当前 " + bytes.length + " 字节，需 ≥ " + MIN_SECRET_BYTES + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    // Access Token: 3小时
    private static final long ACCESS_TOKEN_EXPIRATION = 3 * 60 * 60 * 1000L;

    // Refresh Token (常规): 6小时 (不勾选记住我)
    private static final long REFRESH_TOKEN_NORMAL = 6 * 60 * 60 * 1000L;

    // Refresh Token (记住我): 7天
    private static final long REFRESH_TOKEN_REMEMBER = 7 * 24 * 60 * 60 * 1000L;

    /**
     * 生成 Access Token
     */
    public String generateAccessToken(String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "access");
        return createToken(claims, username, ACCESS_TOKEN_EXPIRATION);
    }

    /**
     * 生成 Refresh Token (核心修改：增加 rememberMe 参数)
     */
    public String generateRefreshToken(String username, boolean rememberMe) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "refresh");

        // 根据是否记住我，决定过期时间
        long expiration = rememberMe ? REFRESH_TOKEN_REMEMBER : REFRESH_TOKEN_NORMAL;

        return createToken(claims, username, expiration);
    }

    private String createToken(Map<String, Object> claims, String subject, long expiration) {
        claims.put("jti", UUID.randomUUID().toString()); // JWT ID：服务端注销黑名单的唯一标识
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    /**
     * 提取 JWT ID（jti），用于服务端注销黑名单。
     *
     * @param token JWT；无 jti 的存量 token 返回 null（注销功能上线前签发的 token 无此字段，按未注销处理）
     */
    public String extractJti(String token) {
        Object jti = extractClaims(token).get("jti");
        return jti == null ? null : String.valueOf(jti);
    }

    /**
     * 计算 token 剩余有效期（毫秒），用作黑名单键的 TTL。
     *
     * @param token 有效 token（过期会抛异常，由调用方按场景捕获）
     */
    public long getRemainingTtl(String token) {
        return extractClaims(token).getExpiration().getTime() - System.currentTimeMillis();
    }

    public boolean validateToken(String token, String username) {
        final String extractedUsername = extractUsername(token);
        return (extractedUsername.equals(username) && !isTokenExpired(token));
    }

    public boolean validateAccessToken(String token, String username) {
        return validateToken(token, username) && "access".equals(extractClaims(token).get("type"));
    }

    public boolean validateRefreshToken(String token, String username) {
        return validateToken(token, username) && "refresh".equals(extractClaims(token).get("type"));
    }

    private Claims extractClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    private boolean isTokenExpired(String token) {
        return extractClaims(token).getExpiration().before(new Date());
    }
}
