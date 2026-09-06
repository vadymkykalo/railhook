package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.Subscription;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.SubscriptionRepository;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.dto.SubscriptionRequest;
import com.webhook.platform.api.dto.SubscriptionResponse;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionServiceTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private TransformationRepository transformationRepository;

    @Mock
    private SubscriptionMatchingCache subscriptionMatchingCache;

    private SubscriptionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID endpointId = UUID.randomUUID();
    private final UUID transformationId = UUID.randomUUID();
    private final UUID otherProjectId = UUID.randomUUID();

    private Project project;
    private Endpoint endpoint;
    private Endpoint foreignEndpoint;
    private Transformation transformation;
    private Transformation foreignTransformation;

    @BeforeEach
    void setUp() {
        service = new SubscriptionService(
                subscriptionRepository, projectRepository, endpointRepository,
                transformationRepository, subscriptionMatchingCache, objectMapper);

        project = Project.builder().id(projectId).organizationId(orgId).name("Test").build();
        endpoint = Endpoint.builder().id(endpointId).projectId(projectId).url("https://ok.com").build();
        foreignEndpoint = Endpoint.builder().id(UUID.randomUUID()).projectId(otherProjectId).url("https://evil.com").build();
        transformation = Transformation.builder().id(transformationId).projectId(projectId).name("t1").template("{}").build();
        foreignTransformation = Transformation.builder().id(UUID.randomUUID()).projectId(otherProjectId).name("t2").template("{}").build();

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(endpointRepository.findById(foreignEndpoint.getId())).thenReturn(Optional.of(foreignEndpoint));
        when(transformationRepository.findById(transformationId)).thenReturn(Optional.of(transformation));
        when(transformationRepository.findById(foreignTransformation.getId())).thenReturn(Optional.of(foreignTransformation));
    }

    private SubscriptionRequest.SubscriptionRequestBuilder baseRequest() {
        return SubscriptionRequest.builder()
                .endpointId(endpointId)
                .eventType("order.created");
    }

    // ── Endpoint ownership in createSubscription ──

    @Test
    void createSubscription_sameProjectEndpoint_succeeds() {
        when(subscriptionRepository.existsByEndpointIdAndEventType(any(), any())).thenReturn(false);
        when(subscriptionRepository.saveAndFlush(any(Subscription.class))).thenAnswer(inv -> {
            Subscription s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            s.setCreatedAt(Instant.now());
            s.setUpdatedAt(Instant.now());
            return s;
        });

        SubscriptionResponse response = service.createSubscription(projectId, baseRequest().build());
        assertThat(response).isNotNull();
        verify(subscriptionRepository).saveAndFlush(any());
    }

    @Test
    void createSubscription_foreignEndpoint_throwsForbidden() {
        SubscriptionRequest request = baseRequest().endpointId(foreignEndpoint.getId()).build();

        assertThatThrownBy(() -> service.createSubscription(projectId, request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Endpoint does not belong to this project");
    }

    @Test
    void createSubscription_nonExistentEndpoint_throwsNotFound() {
        UUID missingId = UUID.randomUUID();
        when(endpointRepository.findById(missingId)).thenReturn(Optional.empty());
        SubscriptionRequest request = baseRequest().endpointId(missingId).build();

        assertThatThrownBy(() -> service.createSubscription(projectId, request))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Endpoint not found");
    }

    // ── Transformation ownership in createSubscription ──

    @Test
    void createSubscription_sameProjectTransformation_succeeds() {
        when(subscriptionRepository.existsByEndpointIdAndEventType(any(), any())).thenReturn(false);
        when(subscriptionRepository.saveAndFlush(any(Subscription.class))).thenAnswer(inv -> {
            Subscription s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            s.setCreatedAt(Instant.now());
            s.setUpdatedAt(Instant.now());
            return s;
        });

        SubscriptionRequest request = baseRequest().transformationId(transformationId).build();
        SubscriptionResponse response = service.createSubscription(projectId, request);
        assertThat(response).isNotNull();
    }

    @Test
    void createSubscription_foreignTransformation_throwsForbidden() {
        SubscriptionRequest request = baseRequest().transformationId(foreignTransformation.getId()).build();

        assertThatThrownBy(() -> service.createSubscription(projectId, request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Transformation does not belong to this project");
    }

    @Test
    void createSubscription_nullTransformation_succeeds() {
        when(subscriptionRepository.existsByEndpointIdAndEventType(any(), any())).thenReturn(false);
        when(subscriptionRepository.saveAndFlush(any(Subscription.class))).thenAnswer(inv -> {
            Subscription s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            s.setCreatedAt(Instant.now());
            s.setUpdatedAt(Instant.now());
            return s;
        });

        SubscriptionRequest request = baseRequest().transformationId(null).build();
        SubscriptionResponse response = service.createSubscription(projectId, request);
        assertThat(response).isNotNull();
    }

    // ── Endpoint ownership in updateSubscription ──

    @Test
    void updateSubscription_foreignEndpoint_throwsForbidden() {
        UUID subId = UUID.randomUUID();
        Subscription existing = Subscription.builder()
                .id(subId).projectId(projectId).endpointId(endpointId).eventType("order.created")
                .enabled(true).orderingEnabled(false).maxAttempts(7).timeoutSeconds(30)
                .retryDelays("60").createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(subscriptionRepository.findById(subId)).thenReturn(Optional.of(existing));

        SubscriptionRequest request = SubscriptionRequest.builder()
                .endpointId(foreignEndpoint.getId()).build();

        assertThatThrownBy(() -> service.updateSubscription(subId, request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Endpoint does not belong to this project");
    }

    // ── Transformation ownership in updateSubscription ──

    @Test
    void updateSubscription_foreignTransformation_throwsForbidden() {
        UUID subId = UUID.randomUUID();
        Subscription existing = Subscription.builder()
                .id(subId).projectId(projectId).endpointId(endpointId).eventType("order.created")
                .enabled(true).orderingEnabled(false).maxAttempts(7).timeoutSeconds(30)
                .retryDelays("60").createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(subscriptionRepository.findById(subId)).thenReturn(Optional.of(existing));

        SubscriptionRequest request = SubscriptionRequest.builder()
                .transformationId(foreignTransformation.getId()).build();

        assertThatThrownBy(() -> service.updateSubscription(subId, request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Transformation does not belong to this project");
    }

    // ── Retry ladder validation ──
    //
    // The ladder is rejected here rather than at delivery time. Before this the worker met a
    // malformed value and quietly substituted a hardcoded array of its own, so a typo bought
    // the customer a retry policy that was neither theirs nor documented anywhere.

    @Test
    void createSubscription_malformedRetryDelays_throwsWithActionableMessage() {
        SubscriptionRequest request = baseRequest().retryDelays("60,oops,900").build();

        assertThatThrownBy(() -> service.createSubscription(projectId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryDelays")
                .hasMessageContaining("tier 2");

        verify(subscriptionRepository, never()).saveAndFlush(any(Subscription.class));
    }

    @Test
    void createSubscription_nonPositiveRetryDelay_throws() {
        SubscriptionRequest request = baseRequest().retryDelays("60,0,900").build();

        assertThatThrownBy(() -> service.createSubscription(projectId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void createSubscription_zeroMaxAttempts_throws() {
        SubscriptionRequest request = baseRequest().maxAttempts(0).build();

        assertThatThrownBy(() -> service.createSubscription(projectId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    void createSubscription_omittedRetryDelays_getsTheDeclaredOutgoingDefault() {
        when(subscriptionRepository.existsByEndpointIdAndEventType(any(), any())).thenReturn(false);
        when(subscriptionRepository.saveAndFlush(any(Subscription.class))).thenAnswer(inv -> {
            Subscription sub = inv.getArgument(0);
            sub.setId(UUID.randomUUID());
            sub.setCreatedAt(Instant.now());
            sub.setUpdatedAt(Instant.now());
            return sub;
        });

        service.createSubscription(projectId, baseRequest().build());

        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRetryDelays()).isEqualTo(RetryLadderDefaults.OUTGOING_DELAYS);
        assertThat(saved.getValue().getMaxAttempts()).isEqualTo(RetryLadderDefaults.OUTGOING_MAX_ATTEMPTS);
    }

    @Test
    void updateSubscription_malformedRetryDelays_throwsAndLeavesTheRowAlone() {
        UUID subId = UUID.randomUUID();
        Subscription existing = Subscription.builder()
                .id(subId).projectId(projectId).endpointId(endpointId).eventType("order.created")
                .enabled(true).orderingEnabled(false).maxAttempts(7).timeoutSeconds(30)
                .retryDelays(RetryLadderDefaults.OUTGOING_DELAYS)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(subscriptionRepository.findById(subId)).thenReturn(Optional.of(existing));

        SubscriptionRequest request = SubscriptionRequest.builder().retryDelays("not,a,ladder").build();

        assertThatThrownBy(() -> service.updateSubscription(subId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryDelays");

        assertThat(existing.getRetryDelays()).isEqualTo(RetryLadderDefaults.OUTGOING_DELAYS);
    }
}
