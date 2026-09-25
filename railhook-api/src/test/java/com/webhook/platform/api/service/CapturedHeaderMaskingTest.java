package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.CapturedRequest;
import com.webhook.platform.api.domain.entity.TestEndpoint;
import com.webhook.platform.api.domain.repository.CapturedRequestRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.TestEndpointRepository;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.ingress.HeaderSanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The test-endpoint capture and the tunnel log stored Authorization headers unmasked.
class CapturedHeaderMaskingTest {

    private static final String MASKED = "***MASKED***";

    @Test
    @DisplayName("the test-endpoint capture stores masked headers, not the ones it received")
    void testEndpointCaptureStoresMaskedHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/hook/abc");
        request.addHeader("Authorization", "Bearer sk_live_do_not_store_this");
        request.addHeader("Cookie", "session=do_not_store_this_either");
        request.addHeader("Content-Type", "application/json");

        TestEndpointRepository endpoints = mock(TestEndpointRepository.class);
        CapturedRequestRepository captures = mock(CapturedRequestRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        TestEndpoint endpoint = new TestEndpoint();
        endpoint.setId(UUID.randomUUID());
        endpoint.setOrganizationId(UUID.randomUUID());
        endpoint.setProjectId(UUID.randomUUID());
        endpoint.setSlug("abc");
        endpoint.setExpiresAt(Instant.now().plusSeconds(3600));
        endpoint.setRequestCount(0);
        when(endpoints.findBySlug("abc")).thenReturn(Optional.of(endpoint));
        ProjectRepository projects = mock(ProjectRepository.class);
        when(projects.existsById(endpoint.getProjectId())).thenReturn(true);
        when(captures.saveAndFlush(any(CapturedRequest.class)))
                .thenAnswer(call -> {
                    CapturedRequest row = call.getArgument(0);
                    row.setId(UUID.randomUUID());
                    row.setReceivedAt(Instant.now());
                    return row;
                });

        TestEndpointService service = new TestEndpointService(
                endpoints, captures, projects,
                mock(TrustedProxyResolver.class), txManager, organizationId -> Optional.empty());

        service.captureRequest("abc", "{}", request);

        ArgumentCaptor<CapturedRequest> stored = ArgumentCaptor.forClass(CapturedRequest.class);
        verify(captures).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getHeaders())
                .doesNotContain("do_not_store_this")
                .contains("application/json");
    }

    @Test
    @DisplayName("the sanitizer keeps what is not a credential")
    void servletCaptureMasksCredentials() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer sk_live_do_not_store_this");
        request.addHeader("Cookie", "session=do_not_store_this_either");
        request.addHeader("X-Api-Key", "key_do_not_store_this");
        request.addHeader("X-Hub-Signature-256", "sha256=do_not_store_this");
        request.addHeader("Content-Type", "application/json");
        request.addHeader("X-Request-Id", "req-42");

        String json = HeaderSanitizer.toJson(request, new ObjectMapper());

        assertThat(json).doesNotContain("do_not_store_this");
        assertThat(json)
                .as("what is not a credential still has to survive — the capture is for debugging")
                .contains("application/json")
                .contains("req-42");
    }

    @Test
    @DisplayName("relayed tunnel headers are masked before the log row, not before the relay")
    void tunnelLogMasksCredentials() {
        // The relay must still forward Authorization verbatim; only the stored copy is masked.
        Map<String, String> relayed = new LinkedHashMap<>();
        relayed.put("Authorization", "Bearer sk_live_do_not_store_this");
        relayed.put("X-Api-Key", "key_do_not_store_this");
        relayed.put("Content-Type", "application/json");

        Map<String, String> stored = HeaderSanitizer.sanitize(relayed);

        assertThat(stored.get("Authorization")).isEqualTo(MASKED);
        assertThat(stored.get("X-Api-Key")).isEqualTo(MASKED);
        assertThat(stored.get("Content-Type")).isEqualTo("application/json");
        assertThat(relayed.get("Authorization"))
                .as("the map handed to the relay must not be mutated")
                .isEqualTo("Bearer sk_live_do_not_store_this");
    }

}
