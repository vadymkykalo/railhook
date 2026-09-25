package com.webhook.platform.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.IngressResponse;
import com.webhook.platform.api.exception.QuotaExceededException;
import com.webhook.platform.api.service.IngressService;
import com.webhook.platform.api.service.ingress.IngressOutcome;
import com.webhook.platform.api.service.ingress.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// A 429 without Retry-After left providers to guess, and the ones that give up dropped the webhook.
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
    void aSlackUrlVerificationIsAnswered200WithTheChallengeAsJson() throws Exception {
        IngressService service = mock(IngressService.class);
        when(service.receiveWebhook(eq("tok"), any(), any()))
                .thenReturn(new IngressOutcome.SlackUrlVerification("3eZbrw1aBm2rZgRNFdxV"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ingress/tok");
        request.setContent("{\"type\":\"url_verification\",\"challenge\":\"3eZbrw1aBm2rZgRNFdxV\"}".getBytes());

        ResponseEntity<?> response = new IngressController(service).receiveWebhook("tok", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(new ObjectMapper().writeValueAsString(response.getBody()))
                .isEqualTo("{\"challenge\":\"3eZbrw1aBm2rZgRNFdxV\"}");
    }

    @Test
    void aSourceOverItsRateLimitIsStillToldToRetryInASecond() {
        ResponseEntity<IngressResponse> response =
                controller.rateLimited(new RateLimitExceededException("limit"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }
}
