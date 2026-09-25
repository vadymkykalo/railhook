package com.webhook.platform.api.security;

import com.webhook.platform.api.service.RedisRateLimiterService;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.webhook.platform.api.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
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
import org.slf4j.LoggerFactory;

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

        verify(rateLimiterService, never()).tryAcquireForOrganization(any(), anyInt());
    }

    @Test
    @DisplayName("a caller over its share is refused, and told when to come back")
    void overLimitIsRefused() throws IOException {
        UUID org = UUID.randomUUID();
        TenantContext.set(org);
        when(rateLimiterService.tryAcquireForOrganization(org, 200)).thenReturn(false);

        assertFalse(interceptor(true, 200).preHandle(request, response, new Object()));

        assertEquals(429, response.getStatus());
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

    @Test
    @DisplayName("a caller cannot forge a log line out of the path it asked for")
    void refusalLogsOneLinePerRequest() throws IOException {
        UUID org = UUID.randomUUID();
        TenantContext.set(org);
        when(rateLimiterService.tryAcquireForOrganization(org, 200)).thenReturn(false);

        Logger logger = (Logger) LoggerFactory.getLogger(OrganizationRateLimitInterceptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest forged = new MockHttpServletRequest(
                    "GET", "/api/v1/deliveries\nWARN  Organization deleted by operator");

            assertFalse(interceptor(true, 200).preHandle(forged, response, new Object()));

            String entry = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("exceeded its API rate limit"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the refusal was not logged at all"));
            assertFalse(entry.contains("\n"), "one request must not be able to write two log lines");
            assertTrue(entry.contains("/api/v1/deliveries_WARN"),
                    "the path still has to be readable — neutralised, not dropped");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
