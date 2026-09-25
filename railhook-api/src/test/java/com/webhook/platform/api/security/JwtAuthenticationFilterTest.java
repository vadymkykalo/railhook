package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.service.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// JwtUtil's ThreadLocal claims cache is safe only because the filter clears it in finally.
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-key-at-least-32-characters-long-for-hmac";

    @Test
    void requestCacheIsEmptyOnThisThreadAfterFilterChainCompletes() throws Exception {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 900_000L, 86_400_000L);
        TokenBlacklistService blacklistService = mock(TokenBlacklistService.class);
        when(blacklistService.isBlacklisted(anyString())).thenReturn(false);
        when(blacklistService.isTokenRevokedByEpoch(any(), any())).thenReturn(false);

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil, blacklistService);

        String token = jwtUtil.generateAccessToken(UUID.randomUUID(), UUID.randomUUID(), MembershipRole.OWNER, null, true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // The token is cached during the request, so the assertion below proves a clear.
        assertThat(jwtUtil.validateToken(token)).isTrue();

        filter.doFilter(request, response, chain);

        @SuppressWarnings("unchecked")
        ThreadLocal<Map<String, Claims>> requestCache =
                (ThreadLocal<Map<String, Claims>>) ReflectionTestUtils.getField(JwtUtil.class, "REQUEST_CACHE");

        assertThat(requestCache.get())
                .as("JwtUtil's per-request claims cache must be empty on this thread once the filter chain " +
                        "returns, or a future request handled on the same thread would start out with another " +
                        "request's already-parsed claims")
                .isEmpty();
    }

    // Access tokens live fifteen minutes, so a signed-out session must be refused per request.
    @Test
    void tokenFromARevokedSessionDoesNotAuthenticate() throws Exception {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 900_000L, 86_400_000L);
        TokenBlacklistService blacklistService = mock(TokenBlacklistService.class);
        UUID sessionId = UUID.randomUUID();
        when(blacklistService.isSessionRevoked(sessionId)).thenReturn(true);

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil, blacklistService);
        String token = jwtUtil.generateAccessToken(
                UUID.randomUUID(), UUID.randomUUID(), MembershipRole.OWNER, sessionId, true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        FilterChain chain = mock(FilterChain.class);

        SecurityContextHolder.clearContext();
        try {
            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(SecurityContextHolder.getContext().getAuthentication())
                    .as("a token whose session has been signed out must leave the request unauthenticated")
                    .isNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // Tokens minted before sessions carry no sid and must keep working until they expire.
    @Test
    void tokenWithoutASessionStillAuthenticates() throws Exception {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 900_000L, 86_400_000L);
        TokenBlacklistService blacklistService = mock(TokenBlacklistService.class);

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil, blacklistService);
        String token = jwtUtil.generateAccessToken(
                UUID.randomUUID(), UUID.randomUUID(), MembershipRole.OWNER, null, true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        FilterChain chain = mock(FilterChain.class);

        SecurityContextHolder.clearContext();
        try {
            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            verify(blacklistService, never()).isSessionRevoked(any());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void requestCacheIsEmptyEvenWhenTokenIsRejected() throws Exception {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 900_000L, 86_400_000L);
        TokenBlacklistService blacklistService = mock(TokenBlacklistService.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil, blacklistService);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-real-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        @SuppressWarnings("unchecked")
        ThreadLocal<Map<String, Claims>> requestCache =
                (ThreadLocal<Map<String, Claims>>) ReflectionTestUtils.getField(JwtUtil.class, "REQUEST_CACHE");

        assertThat(requestCache.get()).isEmpty();
    }
}
