package com.webhook.platform.api.security;

import com.webhook.platform.api.service.RedisRateLimiterService;
import com.webhook.platform.api.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The wall between two tenants sharing one installation.
 *
 * <p>Without it there is a single platform-wide bucket, so one organization looping over its
 * deliveries spends everyone's budget and the rest get 429s for something they did not do.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrganizationRateLimitInterceptorTest {

    @Mock private RedisRateLimiterService rateLimiterService;

    private final HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/deliveries");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    private OrganizationRateLimitInterceptor interceptor(boolean enabled, int perSecond) {
        return new OrganizationRateLimitInterceptor(rateLimiterService, enabled, perSecond);
    }

    @Test
    @DisplayName("off by default, it does not even ask Redis")
    void disabledCostsNothing() throws IOException {
        TenantContext.set(UUID.randomUUID());

        assertTrue(interceptor(false, 200).preHandle(request, response, new Object()));

        // A self-hosted installation has no neighbour to be noisy, and should not pay a round
        // trip per request for the problem it does not have.
        verify(rateLimiterService, never()).tryAcquireForOrganization(any(), anyInt());
    }

    @Test
    @DisplayName("a caller within its share passes through")
    void withinLimitPassesThrough() throws IOException {
        UUID org = UUID.randomUUID();
        TenantContext.set(org);
        when(rateLimiterService.tryAcquireForOrganization(org, 200)).thenReturn(true);

        assertTrue(interceptor(true, 200).preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("a caller over its share is refused, and told when to come back")
    void overLimitIsRefused() throws IOException {
        UUID org = UUID.randomUUID();
        TenantContext.set(org);
        when(rateLimiterService.tryAcquireForOrganization(org, 200)).thenReturn(false);

        assertFalse(interceptor(true, 200).preHandle(request, response, new Object()));

        assertEquals(429, response.getStatus());
        // Without Retry-After a client retries immediately, which is the behaviour that got it
        // rate-limited in the first place.
        assertEquals("1", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("organization_rate_limit"));
    }

    @Test
    @DisplayName("one organization's limit says nothing about another's")
    void limitIsPerOrganization() throws IOException {
        UUID noisy = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();
        when(rateLimiterService.tryAcquireForOrganization(noisy, 200)).thenReturn(false);
        when(rateLimiterService.tryAcquireForOrganization(quiet, 200)).thenReturn(true);

        TenantContext.set(noisy);
        assertFalse(interceptor(true, 200).preHandle(request, new MockHttpServletResponse(), new Object()));

        TenantContext.set(quiet);
        assertTrue(interceptor(true, 200).preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    @DisplayName("the platform admin is not a tenant, and is not charged to one")
    void systemTenantIsNotRateLimited() throws IOException {
        TenantContext.set(TenantContext.SYSTEM);

        assertTrue(interceptor(true, 200).preHandle(request, response, new Object()));

        verify(rateLimiterService, never()).tryAcquireForOrganization(any(), anyInt());
    }

    @Test
    @DisplayName("a request with no scope yet is left alone rather than charged to nobody")
    void unscopedRequestPassesThrough() throws IOException {
        TenantContext.clear();

        assertTrue(interceptor(true, 200).preHandle(request, response, new Object()));

        verify(rateLimiterService, never()).tryAcquireForOrganization(any(), anyInt());
    }
}
