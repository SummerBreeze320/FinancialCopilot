package com.financial.copilot.agent.core.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * <h1>JWT 签名生成与解析校验工具类 (JSON Web Token Utility)</h1>
 * <p>
 * 职责：基于 HMAC-SHA256 算法签发与校验无状态身份令牌 (Access Token 与 Refresh Token)。
 * 严格遵循 JJWT 0.12.x 规范，Payload 包含 userId, username, roles, permissions 等关键主体字段。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class JwtUtils {

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;

    public JwtUtils(
            @Value("${copilot.jwt.secret:FinancialCopilotSecretKeyForJwtAuthentication2026SecureKey}") String secret,
            @Value("${copilot.jwt.access-token-expiration-ms:7200000}") long accessTokenExpirationMs,
            @Value("${copilot.jwt.refresh-token-expiration-ms:604800000}") long refreshTokenExpirationMs) {
        // 保证密钥至少达到 256 位 (32 字节)
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
    }

    /**
     * 为指定用户主体生成 Access Token (默认有效期 2 小时)
     *
     * @param principal 认证用户主体
     * @return JWT 紧凑字符串
     */
    public String generateAccessToken(UserPrincipal principal) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpirationMs);

        return Jwts.builder()
                .subject(principal.getUsername())
                .claim("userId", principal.getUserId())
                .claim("roles", principal.getRoles())
                .claim("permissions", principal.getPermissions())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /**
     * 生成长效 Refresh Token (默认有效期 7 天)
     *
     * @param userId   用户 ID
     * @param username 用户账号
     * @return JWT 紧凑字符串
     */
    public String generateRefreshToken(Long userId, String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenExpirationMs);

        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("type", "REFRESH")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /**
     * 校验并解析 Token 负载 Claims
     *
     * @param token 待校验 Token
     * @return Claims 载荷（若签名无效或过期则抛出异常或返回 null）
     */
    public Claims parseClaims(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.debug("[JWT] Token 解析失败或已过期: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 Token 中提取用户 ID
     *
     * @param token JWT 字符串
     * @return 用户 ID，无效返回 null
     */
    public Long extractUserId(String token) {
        Claims claims = parseClaims(token);
        if (claims == null) return null;
        Object uid = claims.get("userId");
        if (uid instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    /**
     * 从 Token 中提取登录账号
     *
     * @param token JWT 字符串
     * @return 用户名
     */
    public String extractUsername(String token) {
        Claims claims = parseClaims(token);
        return claims != null ? claims.getSubject() : null;
    }

    /**
     * 从 Token 提取角色代码列表
     *
     * @param token JWT 字符串
     * @return 角色代码列表
     */
    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        Claims claims = parseClaims(token);
        if (claims == null) return List.of();
        Object r = claims.get("roles");
        if (r instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    /**
     * 校验 Token 是否未过期且签名合法
     *
     * @param token 待检测 Token
     * @return true 若有效
     */
    public boolean validateToken(String token) {
        Claims claims = parseClaims(token);
        return claims != null && claims.getExpiration().after(new Date());
    }

    public long getAccessTokenExpirationMs() {
        return accessTokenExpirationMs;
    }
}
