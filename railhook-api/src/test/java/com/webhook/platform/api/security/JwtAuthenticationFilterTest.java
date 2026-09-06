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

/**
 * {@link JwtUtil} caches parsed claims in a static
 * {@code ThreadLocal<Map<String, Claims>>} (REQUEST_CACHE) to avoid
 * re-verifying the same token's HMAC signature multiple times within one
 * request. That is only safe because {@link JwtAuthenticationFilter}
 * unconditionally clears it in a {@code finally} block after the filter
 * chain returns - see the javadoc on {@code JwtUtil.REQUEST_CACHE} for the
 * full invariant, including why it would leak a previous request's claims
 * across requests if virtual threads were ever pooled/reused instead of
 * spawned fresh per request.
 *
 * <p>This test proves the clearing half of that invariant holds for a real
 * request through the real filter (not a mocked JwtUtil, which would never
 * touch the actual cache).
 */
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

        // Sanity check: the token is actually parsed (and therefore cached)
        // during the request, so the post-request assertion below is proving
        // something was cleared, not that nothing was ever cached.
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

    /**
     * The half of "sign this device out" that has to happen per request.
     *
     * <p>An access token is self-contained and lives fifteen minutes, so revoking the session's
     * refresh token would leave the device that is being signed out fully authenticated for the
     * rest of that quarter of an hour — on the one screen where somebody is acting because they
     * believe a device is compromised. The token therefore carries its session as a {@code sid}
     * claim and the filter asks about it, exactly as it already asks about the jti blacklist and
     * the per-user revocation epoch.
     */
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

    /**
     * Tokens minted before sessions existed carry no {@code sid}. They must keep working until
     * they expire — refusing them would have signed out everybody who was logged in across the
     * upgrade — and must not be treated as belonging to some session.
     */
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
