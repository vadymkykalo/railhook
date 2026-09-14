package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.IngressResponse;
import com.webhook.platform.api.exception.QuotaExceededException;
import com.webhook.platform.api.service.IngressService;
import com.webhook.platform.api.service.ingress.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What a provider's retry logic reads off a refusal. Every 429 the ingress answers says when to
 * come back; one that did not left each provider to guess, and the ones that give up dropped the
 * webhook.
 */
class IngressControllerTest {

    private final IngressController controller = new IngressController(mock(IngressService.class));

    @Test
    void anOrganizationOverItsQuotaIsAnswered429WithRetryAfter() {
        ResponseEntity<IngressResponse> response = controller.quotaExceeded(
                new QuotaExceededException("events_per_month", 10_000, 10_000, "Free"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("3600");
        assertThat(response.getBody().getError()).isEqualTo("quota_exceeded");
    }

    @Test
    void aSourceOverItsRateLimitIsStillToldToRetryInASecond() {
        ResponseEntity<IngressResponse> response =
                controller.rateLimited(new RateLimitExceededException("limit"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }
}
