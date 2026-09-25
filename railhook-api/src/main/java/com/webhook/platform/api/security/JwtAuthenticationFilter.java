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
                    // A refresh token, or one with no "typ" claim, must not authenticate requests.
                    log.debug("Token jti={} has type={}, expected access, rejecting", jti, tokenType);
                } else if (tokenBlacklistService.isBlacklisted(jti)) {
                    log.debug("Token jti={} is blacklisted, rejecting", jti);
                } else {
                    UUID userId = UUID.fromString(claims.getSubject());

                    UUID sessionId = sessionIdOf(claims);

                    if (tokenBlacklistService.isTokenRevokedByEpoch(userId, claims.getIssuedAt())) {
                        log.debug("Token for user {} was issued before revocation epoch, rejecting", userId);
                    } else if (sessionId != null && tokenBlacklistService.isSessionRevoked(sessionId)) {
                        // Otherwise signing a device out would wait for its access token to expire.
                        log.debug("Token belongs to revoked session {}, rejecting", sessionId);
                    } else if (isDemo(claims) && !request.getRequestURI().startsWith("/api/")) {
                        // Anyone can get a demo token, so it is valid on the API and nowhere else.
                        log.debug("Demo token presented outside /api ({}), rejecting", request.getRequestURI());
                    } else {
                        UUID organizationId = UUID.fromString(claims.get("organizationId", String.class));
                        MembershipRole role = MembershipRole.valueOf(claims.get("role", String.class));

                        // Absent on older tokens, which read as verified.
                        Boolean verifiedClaim = claims.get(JwtUtil.CLAIM_EMAIL_VERIFIED, Boolean.class);

                        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                                userId,
                                organizationId,
                                role,
                                verifiedClaim == null || verifiedClaim,
                                isDemo(claims),
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

    private static boolean isDemo(Claims claims) {
        return Boolean.TRUE.equals(claims.get(JwtUtil.CLAIM_DEMO, Boolean.class));
    }

    /** A malformed value is treated as no session, since it cannot match a real one. */
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
