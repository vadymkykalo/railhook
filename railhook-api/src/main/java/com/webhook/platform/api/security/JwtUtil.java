package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.MembershipRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;
    
    /**
     * Per-request cache of verified claims. Safe only because JwtAuthenticationFilter clears it
     * in a finally block and each request owns its thread until then. An executor that reuses
     * threads across requests without clearing would hand one request another's identity.
     */
    private static final ThreadLocal<Map<String, Claims>> REQUEST_CACHE =
            ThreadLocal.withInitial(ConcurrentHashMap::new);

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration:900000}") long accessTokenExpiration,
            @Value("${jwt.refresh-token-expiration:86400000}") long refreshTokenExpiration) {
        if (secret == null || secret.isBlank() || secret.length() < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set and at least 32 characters. " +
                            "Set it via environment variable JWT_SECRET or property jwt.secret");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    /** Consumers must reject a token whose {@code typ} is not the one they expect. */
    public static final String TOKEN_TYPE_ACCESS = "access";

    public static final String TOKEN_TYPE_REFRESH = "refresh";

    /**
     * The user_sessions row a token was minted for; checked for revocation on every request.
     * A token without it belongs to no session and cannot be signed out individually.
     */
    public static final String CLAIM_SESSION_ID = "sid";

    /** Absent on older tokens, and read as true there so an upgrade signs nobody out. */
    public static final String CLAIM_EMAIL_VERIFIED = "evf";

    public String generateAccessToken(UUID userId, UUID organizationId, MembershipRole role, UUID sessionId,
                                      boolean emailVerified) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId.toString());
        claims.put("organizationId", organizationId.toString());
        claims.put("role", role.name());
        claims.put("typ", TOKEN_TYPE_ACCESS);
        claims.put(CLAIM_EMAIL_VERIFIED, emailVerified);
        if (sessionId != null) {
            claims.put(CLAIM_SESSION_ID, sessionId.toString());
        }

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .claims(claims)
                .subject(userId.toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiration))
                .signWith(secretKey)
                .compact();
    }

    public static final String CLAIM_DEMO = "demo";

    /**
     * No refresh token and no session row, so a demo token cannot be renewed, listed or turned
     * into a CLI grant.
     */
    public String generateDemoAccessToken(UUID userId, UUID organizationId, Duration ttl) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .claim("userId", userId.toString())
                .claim("organizationId", organizationId.toString())
                .claim("role", MembershipRole.VIEWER.name())
                .claim("typ", TOKEN_TYPE_ACCESS)
                .claim(CLAIM_EMAIL_VERIFIED, true)
                .claim(CLAIM_DEMO, true)
                .subject(userId.toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttl.toMillis()))
                .signWith(secretKey)
                .compact();
    }

    public String generateRefreshToken(UUID userId, UUID sessionId) {
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .claim("typ", TOKEN_TYPE_REFRESH)
                .subject(userId.toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiration));
        if (sessionId != null) {
            builder.claim(CLAIM_SESSION_ID, sessionId.toString());
        }
        return builder.signWith(secretKey).compact();
    }

    public long getRefreshTokenExpirationMs() {
        return refreshTokenExpiration;
    }

    /** Callers must treat {@code null} as "no session", never as "any session". */
    public UUID getSessionIdFromToken(String token) {
        String sessionId = parseToken(token).get(CLAIM_SESSION_ID, String.class);
        if (sessionId == null) {
            return null;
        }
        try {
            return UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Claims parseToken(String token) {
        Map<String, Claims> cache = REQUEST_CACHE.get();
        Claims cached = cache.get(token);
        if (cached != null) {
            return cached;
        }

        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        cache.put(token, claims);
        return claims;
    }
    
    public static void clearCache() {
        REQUEST_CACHE.remove();
    }

    public UUID getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return UUID.fromString(claims.getSubject());
    }

    public UUID getOrganizationIdFromToken(String token) {
        Claims claims = parseToken(token);
        return UUID.fromString(claims.get("organizationId", String.class));
    }

    public MembershipRole getRoleFromToken(String token) {
        Claims claims = parseToken(token);
        return MembershipRole.valueOf(claims.get("role", String.class));
    }

    public String getJtiFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getId();
    }

    /** Callers must treat a null or unexpected value as the wrong token type. */
    public String getTokenType(String token) {
        Claims claims = parseToken(token);
        return claims.get("typ", String.class);
    }

    public Date getExpirationFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getExpiration();
    }

    public Date getIssuedAtFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getIssuedAt();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
