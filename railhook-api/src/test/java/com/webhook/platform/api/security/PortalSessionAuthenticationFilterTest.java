package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.entity.PortalSession;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.PortalSessionRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.service.RedisRateLimiterService;
import com.webhook.platform.common.util.CryptoUtils;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortalSessionAuthenticationFilterTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final String TOKEN = "rhp_token";

    private final PortalSessionRepository sessions = mock(PortalSessionRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final RedisRateLimiterService rateLimiter = mock(RedisRateLimiterService.class);
    private final PortalSessionAuthenticationFilter filter = new PortalSessionAuthenticationFilter(
            sessions, projects, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC), 20);

    private final PortalSession session = PortalSession.builder()
            .id(UUID.randomUUID())
            .organizationId(UUID.randomUUID())
            .projectId(UUID.randomUUID())
            .consumerId(UUID.randomUUID())
            .tokenHash(CryptoUtils.hashApiKey(TOKEN))
            .expiresAt(NOW.plusSeconds(60))
            .build();

    @BeforeEach
    void setUp() {
        when(sessions.findByTokenHash(CryptoUtils.hashApiKey(TOKEN))).thenReturn(Optional.of(session));
        when(projects.findById(session.getProjectId())).thenReturn(Optional.of(new Project()));
        when(rateLimiter.tryAcquireForPortalSession(any(UUID.class), anyInt())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesALiveSessionAsItsConsumer() throws Exception {
        Authentication authentication = run("/api/v1/portal/endpoints", TOKEN).authentication;

        assertThat(authentication).isInstanceOf(PortalSessionAuthenticationToken.class);
        PortalSessionAuthenticationToken portal = (PortalSessionAuthenticationToken) authentication;
        assertThat(portal.getConsumerId()).isEqualTo(session.getConsumerId());
        assertThat(portal.getOrganizationId()).isEqualTo(session.getOrganizationId());
        assertThat(portal.getAuthorities()).extracting(Object::toString)
                .containsExactly(PortalSessionAuthenticationToken.AUTHORITY);
        assertThat(portal.getCredentials()).as("the token is not kept on the authentication").isNull();
    }

    @Test
    void neverLooksAtATokenOutsideThePortal() throws Exception {
        assertThat(run("/api/v1/projects/p/endpoints", TOKEN).authentication).isNull();
        verify(sessions, never()).findByTokenHash(anyString());
    }

    @Test
    void anExpiredSessionAuthenticatesNothing() throws Exception {
        session.setExpiresAt(NOW);
        assertThat(run("/api/v1/portal/session", TOKEN).authentication).isNull();
    }

    @Test
    void aSessionWhoseProjectIsGoneAuthenticatesNothing() throws Exception {
        when(projects.findById(session.getProjectId())).thenReturn(Optional.empty());
        assertThat(run("/api/v1/portal/session", TOKEN).authentication).isNull();
    }

    @Test
    void aBearerThatIsNotAPortalTokenIsLeftForTheOtherFilters() throws Exception {
        assertThat(run("/api/v1/portal/session", "eyJhbGciOiJIUzI1NiJ9.e30.x").authentication).isNull();
        verify(sessions, never()).findByTokenHash(anyString());
    }

    @Test
    void aSessionOverItsRateLimitIsTurnedAwayBeforeTheHandler() throws Exception {
        when(rateLimiter.tryAcquireForPortalSession(session.getId(), 20)).thenReturn(false);

        Result result = run("/api/v1/portal/deliveries", TOKEN);

        assertThat(result.response.getStatus()).isEqualTo(429);
        assertThat(result.response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(result.chainCalled).isFalse();
    }

    private record Result(Authentication authentication, MockHttpServletResponse response, boolean chainCalled) {
    }

    private Result run(String uri, String bearer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.addHeader("Authorization", "Bearer " + bearer);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] called = {false};
        Authentication[] seen = {null};
        FilterChain chain = (req, res) -> {
            called[0] = true;
            seen[0] = SecurityContextHolder.getContext().getAuthentication();
        };
        filter.doFilter(request, response, chain);
        return new Result(seen[0], response, called[0]);
    }
}
