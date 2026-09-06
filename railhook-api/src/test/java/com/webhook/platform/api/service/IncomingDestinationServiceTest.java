package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.dto.IncomingDestinationRequest;
import com.webhook.platform.api.dto.IncomingDestinationResponse;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncomingDestinationServiceTest {

    @Mock
    private IncomingDestinationRepository destinationRepository;
    @Mock
    private IncomingSourceRepository sourceRepository;
    @Mock
    private TransformationRepository transformationRepository;

    private IncomingDestinationService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID destId = UUID.randomUUID();

    private IncomingSource source;

    @BeforeEach
    void setUp() throws Exception {
        EncryptionKeyRegistry registry = createTestRegistry(
                "test_encryption_key_32_chars_pad", "test_salt");
        service = new IncomingDestinationService(
                destinationRepository, sourceRepository,
                transformationRepository,
                registry,
                true, List.of()
        );
        source = IncomingSource.builder()
                .id(sourceId).projectId(projectId).name("src")
                .slug("src").providerType(ProviderType.GENERIC)
                .status(IncomingSourceStatus.ACTIVE)
                .ingressPathToken("tok").verificationMode(VerificationMode.NONE)
                .build();
    }

    private IncomingDestination buildDest() {
        return IncomingDestination.builder()
                .id(destId).incomingSourceId(sourceId)
                .url("https://example.com/hook")
                .authType(IncomingAuthType.NONE)
                .enabled(true).maxAttempts(5).timeoutSeconds(30)
                .retryDelays("60,300,900")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }

    private void stubOwnership() {
        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
    }


    /**
     * Every service under test now reads its organization from the ambient tenant scope instead
     * of taking it as a parameter. A unit test has no request to establish one, so it
     * enters the scope itself; without this the first call fails with TenantNotResolvedException.
     */
    @BeforeEach
    void enterTenantScope() {
        TenantContext.set(orgId);
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    @Test
    void createDestination_success() {
        stubOwnership();
        when(destinationRepository.saveAndFlush(any(IncomingDestination.class))).thenAnswer(inv -> {
            IncomingDestination d = inv.getArgument(0);
            d.setId(destId);
            d.setCreatedAt(Instant.now());
            d.setUpdatedAt(Instant.now());
            return d;
        });

        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .authType(IncomingAuthType.BEARER)
                .authConfig("{\"token\":\"secret123\"}")
                .maxAttempts(3)
                .timeoutSeconds(15)
                .retryDelays("30,60")
                .build();

        IncomingDestinationResponse response = service.createDestination(sourceId, request);

        assertThat(response.getId()).isEqualTo(destId);
        assertThat(response.getUrl()).isEqualTo("https://example.com/hook");
        assertThat(response.getAuthType()).isEqualTo(IncomingAuthType.BEARER);
        assertThat(response.isAuthConfigured()).isTrue();
        assertThat(response.getMaxAttempts()).isEqualTo(3);
        assertThat(response.getTimeoutSeconds()).isEqualTo(15);
        assertThat(response.getRetryDelays()).isEqualTo("30,60");

        ArgumentCaptor<IncomingDestination> captor = ArgumentCaptor.forClass(IncomingDestination.class);
        verify(destinationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getAuthConfigEncrypted()).isNotNull();
    }

    @Test
    void createDestination_defaultValues() {
        stubOwnership();
        when(destinationRepository.saveAndFlush(any())).thenAnswer(inv -> {
            IncomingDestination d = inv.getArgument(0);
            d.setId(destId);
            d.setCreatedAt(Instant.now());
            d.setUpdatedAt(Instant.now());
            return d;
        });

        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .build();

        IncomingDestinationResponse response = service.createDestination(sourceId, request);

        assertThat(response.getAuthType()).isEqualTo(IncomingAuthType.NONE);
        assertThat(response.isEnabled()).isTrue();
        assertThat(response.getMaxAttempts()).isEqualTo(5);
        assertThat(response.getTimeoutSeconds()).isEqualTo(30);
    }


    @Test
    void getDestination_success() {
        IncomingDestination dest = buildDest();
        when(destinationRepository.findById(destId)).thenReturn(Optional.of(dest));
        stubOwnership();

        IncomingDestinationResponse response = service.getDestination(destId);
        assertThat(response.getUrl()).isEqualTo("https://example.com/hook");
    }

    @Test
    void getDestination_notFound() {
        when(destinationRepository.findById(destId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDestination(destId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void listDestinations_success() {
        stubOwnership();
        when(destinationRepository.findByIncomingSourceId(eq(sourceId), any()))
                .thenReturn(new PageImpl<>(List.of(buildDest())));

        Page<IncomingDestinationResponse> page = service.listDestinations(sourceId, PageRequest.of(0, 20));
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void updateDestination_success() {
        IncomingDestination dest = buildDest();
        when(destinationRepository.findById(destId)).thenReturn(Optional.of(dest));
        stubOwnership();
        when(destinationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://updated.com/hook")
                .authType(IncomingAuthType.BASIC)
                .enabled(false)
                .maxAttempts(10)
                .build();

        IncomingDestinationResponse response = service.updateDestination(destId, request);

        assertThat(response.getUrl()).isEqualTo("https://updated.com/hook");
        assertThat(response.getAuthType()).isEqualTo(IncomingAuthType.BASIC);
        assertThat(response.isEnabled()).isFalse();
        assertThat(response.getMaxAttempts()).isEqualTo(10);
    }

    // ── Transformation project ownership ──

    @Test
    void createDestination_foreignTransformation_throwsForbidden() {
        stubOwnership();

        UUID foreignProjectId = UUID.randomUUID();
        UUID transformId = UUID.randomUUID();
        com.webhook.platform.api.domain.entity.Transformation foreignTransformation =
                com.webhook.platform.api.domain.entity.Transformation.builder()
                        .id(transformId).projectId(foreignProjectId).name("foreign").template("{}").build();
        when(transformationRepository.findById(transformId)).thenReturn(Optional.of(foreignTransformation));

        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .transformationId(transformId.toString())
                .build();

        assertThatThrownBy(() -> service.createDestination(sourceId, request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Transformation does not belong to this project");
    }

    @Test
    void createDestination_sameProjectTransformation_succeeds() {
        stubOwnership();
        UUID transformId = UUID.randomUUID();
        com.webhook.platform.api.domain.entity.Transformation sameTransformation =
                com.webhook.platform.api.domain.entity.Transformation.builder()
                        .id(transformId).projectId(projectId).name("same").template("{}").build();
        when(transformationRepository.findById(transformId)).thenReturn(Optional.of(sameTransformation));
        when(destinationRepository.saveAndFlush(any())).thenAnswer(inv -> {
            IncomingDestination d = inv.getArgument(0);
            d.setId(destId);
            d.setCreatedAt(Instant.now());
            d.setUpdatedAt(Instant.now());
            return d;
        });

        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .transformationId(transformId.toString())
                .build();

        IncomingDestinationResponse response = service.createDestination(sourceId, request);
        assertThat(response.getTransformationId()).isEqualTo(transformId);
    }

    @Test
    void updateDestination_foreignTransformation_throwsForbidden() {
        IncomingDestination dest = buildDest();
        when(destinationRepository.findById(destId)).thenReturn(Optional.of(dest));
        stubOwnership();

        UUID foreignProjectId = UUID.randomUUID();
        UUID transformId = UUID.randomUUID();
        com.webhook.platform.api.domain.entity.Transformation foreignTransformation =
                com.webhook.platform.api.domain.entity.Transformation.builder()
                        .id(transformId).projectId(foreignProjectId).name("foreign").template("{}").build();
        when(transformationRepository.findById(transformId)).thenReturn(Optional.of(foreignTransformation));

        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .transformationId(transformId.toString())
                .build();

        assertThatThrownBy(() -> service.updateDestination(destId, request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Transformation does not belong to this project");
    }

    @Test
    void updateDestination_blankTransformationId_detachesTheTemplate() {
        IncomingDestination dest = buildDest();
        dest.setTransformationId(UUID.randomUUID());
        when(destinationRepository.findById(destId)).thenReturn(Optional.of(dest));
        when(destinationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        stubOwnership();

        // Same signal that clears payloadTransform. There used to be none for this field, so a
        // destination that acquired a transformation was stuck with one for good.
        service.updateDestination(destId, IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .transformationId("")
                .build());

        assertThat(dest.getTransformationId()).isNull();
        verify(transformationRepository, never()).findById(any());
    }

    @Test
    void updateDestination_omittedTransformationId_leavesItAlone() {
        IncomingDestination dest = buildDest();
        UUID attached = UUID.randomUUID();
        dest.setTransformationId(attached);
        when(destinationRepository.findById(destId)).thenReturn(Optional.of(dest));
        when(destinationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        stubOwnership();

        service.updateDestination(destId, IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .build());

        assertThat(dest.getTransformationId()).isEqualTo(attached);
    }

    @Test
    void updateDestination_malformedTransformationId_isRefused() {
        IncomingDestination dest = buildDest();
        when(destinationRepository.findById(destId)).thenReturn(Optional.of(dest));
        stubOwnership();

        assertThatThrownBy(() -> service.updateDestination(destId, IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .transformationId("not-a-uuid")
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a UUID");
    }

    @Test
    void deleteDestination_success() {
        IncomingDestination dest = buildDest();
        when(destinationRepository.findById(destId)).thenReturn(Optional.of(dest));
        stubOwnership();

        service.deleteDestination(destId);

        verify(destinationRepository).delete(dest);
    }

    private static EncryptionKeyRegistry createTestRegistry(String key, String salt) throws Exception {
        EncryptionKeyRegistry registry = new EncryptionKeyRegistry();
        setField(registry, "singleKey", key);
        setField(registry, "multiKeys", "");
        setField(registry, "configuredActiveVersion", 0);
        setField(registry, "salt", salt);
        var init = registry.getClass().getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(registry);
        return registry;
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    // ── Retry ladder validation ──
    //
    // Mirrors SubscriptionServiceTest: a malformed ladder is rejected at write time, not met
    // by the worker and silently replaced with a hardcoded array.

    @Test
    void createDestination_malformedRetryDelays_throwsWithActionableMessage() {
        stubOwnership();

        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .retryDelays("60,oops,900")
                .build();

        assertThatThrownBy(() -> service.createDestination(sourceId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryDelays")
                .hasMessageContaining("tier 2");

        verify(destinationRepository, never()).saveAndFlush(any(IncomingDestination.class));
    }

    @Test
    void createDestination_zeroMaxAttempts_throws() {
        stubOwnership();

        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .maxAttempts(0)
                .build();

        assertThatThrownBy(() -> service.createDestination(sourceId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    void createDestination_omittedRetryDelays_getsTheDeclaredIncomingDefault() {
        stubOwnership();
        when(destinationRepository.saveAndFlush(any())).thenAnswer(inv -> {
            IncomingDestination d = inv.getArgument(0);
            d.setId(destId);
            d.setCreatedAt(Instant.now());
            d.setUpdatedAt(Instant.now());
            return d;
        });

        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .build();

        service.createDestination(sourceId, request);

        ArgumentCaptor<IncomingDestination> saved = ArgumentCaptor.forClass(IncomingDestination.class);
        verify(destinationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRetryDelays()).isEqualTo(RetryLadderDefaults.INCOMING_DELAYS);
        assertThat(saved.getValue().getMaxAttempts()).isEqualTo(RetryLadderDefaults.INCOMING_MAX_ATTEMPTS);
    }

    @Test
    void incomingDefaultLadder_deliberatelyShorterThanOutgoing() {
        // Guards the decision recorded in RetryLadderDefaults: the two directions
        // differ on purpose. A future "tidy-up" that aligns them should fail here first and go
        // read why.
        assertThat(RetryLadderDefaults.INCOMING_DELAYS).isNotEqualTo(RetryLadderDefaults.OUTGOING_DELAYS);
        assertThat(RetryLadderDefaults.INCOMING_MAX_ATTEMPTS).isLessThan(RetryLadderDefaults.OUTGOING_MAX_ATTEMPTS);
    }
}
