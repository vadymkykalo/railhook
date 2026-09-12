package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.dto.DeliveryDryRunRequest;
import com.webhook.platform.api.dto.DeliveryDryRunResponse;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The dry-run mints a real signature, which makes it the one read path where getting the scope
 * wrong hands out something that works elsewhere.
 *
 * <p>{@code @TenantId} confines it to the organization, and the interceptor confines the
 * {@code {projectId}} in the URI — but the endpoint arrives in the request <em>body</em>, where
 * neither can see it. An organization with two projects is the ordinary case, not an exotic one,
 * and an API key is issued against one project rather than against all of them.
 */
class DeliveryDryRunServiceTest {

    private static final UUID CALLERS_PROJECT = UUID.randomUUID();
    private static final UUID ANOTHER_PROJECT = UUID.randomUUID();

    private EndpointRepository endpointRepository;
    private DeliveryDryRunService service;

    @BeforeEach
    void setUp() {
        endpointRepository = mock(EndpointRepository.class);
        TransformationRepository transformationRepository = mock(TransformationRepository.class);
        EncryptionKeyRegistry registry = mock(EncryptionKeyRegistry.class);
        lenient().when(registry.decryptWithFallback(anyString(), anyString(), anyInt()))
                .thenReturn("the-endpoint-signing-secret");
        lenient().when(transformationRepository.findById(any(UUID.class)))
                .thenReturn(Optional.<Transformation>empty());

        service = new DeliveryDryRunService(
                transformationRepository, endpointRepository, new ObjectMapper(), registry);
    }

    private Endpoint endpointIn(UUID projectId) {
        Endpoint endpoint = new Endpoint();
        endpoint.setId(UUID.randomUUID());
        endpoint.setProjectId(projectId);
        endpoint.setUrl("https://receiver.example.com/hook");
        endpoint.setEnabled(true);
        endpoint.setSecretEncrypted("cipher");
        endpoint.setSecretIv("iv");
        endpoint.setEncryptionKeyVersion(1);
        return endpoint;
    }

    private DeliveryDryRunRequest requestFor(Endpoint endpoint) {
        DeliveryDryRunRequest request = new DeliveryDryRunRequest();
        request.setPayload("{\"amount\":1}");
        request.setEndpointId(endpoint.getId());
        return request;
    }

    @Test
    @DisplayName("an endpoint of the caller's own project is signed for, as before")
    void signsForItsOwnProject() {
        Endpoint endpoint = endpointIn(CALLERS_PROJECT);
        when(endpointRepository.findById(endpoint.getId())).thenReturn(Optional.of(endpoint));

        DeliveryDryRunResponse response = service.dryRun(CALLERS_PROJECT, requestFor(endpoint));

        assertThat(response.getSignature()).isNotBlank();
        assertThat(response.getEndpointUrl()).isEqualTo("https://receiver.example.com/hook");
        assertThat(response.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("an endpoint belonging to a sibling project is neither signed for nor described")
    void refusesASiblingProjectsEndpoint() {
        // Two things leaked, and the signature is the worse of them: a valid X-Signature over a
        // body of the caller's choosing is a webhook the sibling project's receiver will accept
        // as genuine. The URL told them where to send it.
        Endpoint endpoint = endpointIn(ANOTHER_PROJECT);
        when(endpointRepository.findById(endpoint.getId())).thenReturn(Optional.of(endpoint));

        DeliveryDryRunResponse response = service.dryRun(CALLERS_PROJECT, requestFor(endpoint));

        assertThat(response.getSignature()).isNull();
        assertThat(response.getEndpointUrl()).isNull();
        assertThat(response.getErrors()).isNotEmpty();
    }

    @Test
    @DisplayName("it does not say which of the two it was, because that is an oracle")
    void doesNotDistinguishWrongProjectFromMissing() {
        // "Not found" and "not yours" are the same answer everywhere else in this codebase —
        // findById under @TenantId returns empty for another organization's row rather than 403.
        // Saying "wrong project" here would turn the dry-run into a way to enumerate endpoint ids.
        Endpoint endpoint = endpointIn(ANOTHER_PROJECT);
        when(endpointRepository.findById(endpoint.getId())).thenReturn(Optional.of(endpoint));
        DeliveryDryRunResponse foreign = service.dryRun(CALLERS_PROJECT, requestFor(endpoint));

        UUID absent = UUID.randomUUID();
        when(endpointRepository.findById(absent)).thenReturn(Optional.empty());
        DeliveryDryRunRequest missingRequest = new DeliveryDryRunRequest();
        missingRequest.setPayload("{\"amount\":1}");
        missingRequest.setEndpointId(absent);
        DeliveryDryRunResponse missing = service.dryRun(CALLERS_PROJECT, missingRequest);

        assertThat(foreign.getErrors().get(0).replace(endpoint.getId().toString(), "ID"))
                .isEqualTo(missing.getErrors().get(0).replace(absent.toString(), "ID"));
    }
}
