package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.service.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.slf4j.MDC;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    
    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, TokenBlacklistService tokenBlacklistService) {
        this.jwtUtil = jwtUtil;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(AUTHORIZATION_HEADER);

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());
            
            try {
                Claims claims = jwtUtil.parseToken(token);

                String jti = claims.getId();
                String tokenType = claims.get("typ", String.class);
                if (!JwtUtil.TOKEN_TYPE_ACCESS.equals(tokenType)) {
                    // Deliberate rejection of anything that isn't an access token -- a refresh
                    // token (or a legacy token with no "typ" claim) must not authenticate
                    // API requests. Previously this only failed by accident (NPE below on the
                    // missing "organizationId" claim, swallowed by the catch block).
                    log.debug("Token jti={} has type={}, expected access, rejecting", jti, tokenType);
                } else if (tokenBlacklistService.isBlacklisted(jti)) {
                    log.debug("Token jti={} is blacklisted, rejecting", jti);
                } else {
                    UUID userId = UUID.fromString(claims.getSubject());

                    UUID sessionId = sessionIdOf(claims);

                    if (tokenBlacklistService.isTokenRevokedByEpoch(userId, claims.getIssuedAt())) {
                        log.debug("Token for user {} was issued before revocation epoch, rejecting", userId);
                    } else if (sessionId != null && tokenBlacklistService.isSessionRevoked(sessionId)) {
                        // Without this, "sign this device out" would only take effect once the
                        // access token already in that device's hands expired on its own -- a
                        // promise kept a quarter of an hour late, on the one screen where a user
                        // is acting because they believe a device is compromised.
                        log.debug("Token belongs to revoked session {}, rejecting", sessionId);
                    } else {
                        UUID organizationId = UUID.fromString(claims.get("organizationId", String.class));
                        MembershipRole role = MembershipRole.valueOf(claims.get("role", String.class));

                        // Absent on tokens minted before the claim existed; those read as
                        // verified rather than signing every live session out on upgrade.
                        Boolean verifiedClaim = claims.get(JwtUtil.CLAIM_EMAIL_VERIFIED, Boolean.class);

                        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                                userId,
                                organizationId,
                                role,
                                verifiedClaim == null || verifiedClaim,
                                Collections.emptyList()
                        );
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        MDC.put("organizationId", organizationId.toString());
                        MDC.put("userId", userId.toString());
                    }
                }
            } catch (Exception e) {
                log.debug("JWT validation failed: {}", e.getMessage());
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("organizationId");
            MDC.remove("userId");
            MDC.remove("projectId");
            JwtUtil.clearCache();
        }
    }

    /**
     * The session a token names, or {@code null} when it names none — which is the case for
     * every token minted before sessions existed. A malformed value is also {@code null}: it
     * cannot match a real session, and treating it as one would reject a token on a typo.
     */
    private static UUID sessionIdOf(Claims claims) {
        String raw = claims.get(JwtUtil.CLAIM_SESSION_ID, String.class);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.debug("Token carries an unparseable {} claim, treating it as sessionless",
                    JwtUtil.CLAIM_SESSION_ID);
            return null;
        }
    }
}
