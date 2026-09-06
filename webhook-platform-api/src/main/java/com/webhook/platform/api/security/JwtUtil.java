package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.MembershipRole;
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
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;
    
    /**
     * Request-scoped cache to avoid re-verifying the same token's HMAC signature
     * multiple times within one request.
     *
     * <p><b>Invariant:</b> this is only safe because it is a {@code static}
     * field shared across every {@code JwtUtil} instance on a given thread, and
     * {@link JwtAuthenticationFilter#doFilterInternal} unconditionally calls
     * {@link #clearCache()} in a {@code finally} block after the filter chain
     * returns - so by the time a thread is free to pick up its next unit of work,
     * this thread's entry is gone. That holds today because Spring MVC's default
     * (non-virtual-thread) servlet model dedicates one pooled platform thread to a
     * request for its full duration and only returns that thread to the pool
     * afterward, i.e. "this thread's next unit of work" always means "this
     * thread's next HTTP request".
     *
     * <p><b>This breaks if virtual threads are ever enabled</b>
     * ({@code spring.threads.virtual.enabled=true}) without revisiting this class,
     * UNLESS the virtual-thread executor keeps spawning one fresh (never reused)
     * virtual thread per request, as Tomcot/Spring's own virtual-thread support
     * does today (Tomcat's virtual-thread support spawns a fresh virtual thread
     * per request rather than pooling them) - because then there is no "next
     * request on this thread" for stale entries to leak into in the first place.
     * The trap is any executor that pools/reuses virtual threads across requests
     * (e.g. a fixed-size worker pool built on virtual threads, or manually
     * routing requests through a shared virtual-thread pool): under thread reuse,
     * an entry this class cached for one request and never got a chance to clear
     * would answer a later {@code parseToken} call on that same reused thread
     * with the wrong request's Claims - a genuine cross-request identity leak,
     * not just stale/duplicate work.
     * {@link com.webhook.platform.api.security.JwtAuthenticationFilterTest} pins
     * the clearing half of this invariant (real filter, real cache, asserts empty
     * after the chain returns); it cannot by itself prove thread-reuse safety,
     * which depends on how the servlet container schedules requests onto threads,
     * not on anything in this class.
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

    /**
     * Value of the {@code typ} claim stamped on every access token. Consumers (notably
     * {@code JwtAuthenticationFilter}) must reject any bearer token whose {@code typ} is
     * not this value, rather than accepting anything that merely parses.
     */
    public static final String TOKEN_TYPE_ACCESS = "access";

    /**
     * Value of the {@code typ} claim stamped on every refresh token. Consumers (notably
     * {@code AuthService#refreshToken}) must reject any token presented to the refresh
     * endpoint whose {@code typ} is not this value.
     */
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    /**
     * Name of the claim naming the {@code user_sessions} row a token was minted for.
     *
     * <p>It is what makes revoking one session mean something to an access token, which is
     * otherwise self-contained for its whole fifteen minutes:
     * {@link JwtAuthenticationFilter} asks {@code TokenBlacklistService.isSessionRevoked} about
     * it on every request. Tokens minted before sessions existed carry no {@code sid}, and are
     * treated as belonging to no session rather than to some session — they still authenticate
     * until they expire, and simply cannot be individually signed out.
     */
    public static final String CLAIM_SESSION_ID = "sid";

    /**
     * Name of the claim saying the account has proved it owns its address.
     *
     * <p>Absent on every token minted before this existed, and read as {@code true} there —
     * see {@code JwtAuthenticationFilter}. Treating absence as unverified would have signed
     * out every live session on upgrade to enforce a rule that, on the deployment shape where
     * mail is off, has nothing to enforce.
     */
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

    /** How long a refresh token minted now would live — what a session's expiry is set from. */
    public long getRefreshTokenExpirationMs() {
        return refreshTokenExpiration;
    }

    /**
     * The session a token belongs to, or {@code null} for a token minted before sessions
     * existed. Callers must treat {@code null} as "no session", never as "any session".
     */
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
        // Check cache first to avoid redundant HMAC verification
        Map<String, Claims> cache = REQUEST_CACHE.get();
        Claims cached = cache.get(token);
        if (cached != null) {
            return cached;
        }
        
        // Parse and cache
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        cache.put(token, claims);
        return claims;
    }
    
    /**
     * Clears the request-scoped token cache.
     * Should be called at the end of request processing (e.g., in filter's finally block).
     */
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

    /**
     * Returns the {@code typ} claim ({@link #TOKEN_TYPE_ACCESS} or {@link #TOKEN_TYPE_REFRESH}),
     * or {@code null} for tokens issued before this claim existed. Callers must treat a
     * {@code null}/unexpected value as "wrong token type", not as "any type is fine".
     */
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
