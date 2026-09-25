package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.dto.IncomingDestinationRequest;
import com.webhook.platform.api.dto.IncomingDestinationResponse;
import com.webhook.platform.api.exception.ForbiddenException;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
                true, List.of(),
                new RetryLadderEscalationCap(96, 24)
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
        when(sourceRepository.findByIdAndProjectId(sourceId, projectId)).thenReturn(Optional.of(source));
    }

    @BeforeEach
    void enterTenantScope() {
        TenantContext.set(orgId);
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    @Test
    void createDestination_storesTheAuthConfigEncrypted() {
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

        IncomingDestinationResponse response = service.createDestination(projectId, sourceId, request);

        assertThat(response.isAuthConfigured()).isTrue();

        ArgumentCaptor<IncomingDestination> captor = ArgumentCaptor.forClass(IncomingDestination.class);
        verify(destinationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getAuthConfigEncrypted()).isNotNull().doesNotContain("secret123");
    }

    @Test
    void createDestination_foreignTransformation_throwsForbidden() {
        stubOwnership();

        UUID foreignProjectId = UUID.randomUUID();
        UUID transformId = UUID.randomUUID();
        Transformation foreignTransformation =
                Transformation.builder()
                        .id(transformId).projectId(foreignProjectId).name("foreign").template("{}").build();
        when(transformationRepository.findById(transformId)).thenReturn(Optional.of(foreignTransformation));

        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .transformationId(transformId.toString())
                .build();

        assertThatThrownBy(() -> service.createDestination(projectId, sourceId, request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Transformation does not belong to this project");
    }

    @Test
    void updateDestination_foreignTransformation_throwsForbidden() {
        IncomingDestination dest = buildDest();
        when(destinationRepository.findByIdAndIncomingSourceId(destId, sourceId)).thenReturn(Optional.of(dest));
        stubOwnership();

        UUID foreignProjectId = UUID.randomUUID();
        UUID transformId = UUID.randomUUID();
        Transformation foreignTransformation =
                Transformation.builder()
                        .id(transformId).projectId(foreignProjectId).name("foreign").template("{}").build();
        when(transformationRepository.findById(transformId)).thenReturn(Optional.of(foreignTransformation));

        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .transformationId(transformId.toString())
                .build();

        assertThatThrownBy(() -> service.updateDestination(projectId, sourceId, destId, request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Transformation does not belong to this project");
    }

    @Test
    void updateDestination_blankTransformationId_detachesTheTemplate() {
        IncomingDestination dest = buildDest();
        dest.setTransformationId(UUID.randomUUID());
        when(destinationRepository.findByIdAndIncomingSourceId(destId, sourceId)).thenReturn(Optional.of(dest));
        lenient().when(destinationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        stubOwnership();

        // There used to be no way to detach a transformation once attached.
        service.updateDestination(projectId, sourceId, destId, IncomingDestinationRequest.builder()
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
        when(destinationRepository.findByIdAndIncomingSourceId(destId, sourceId)).thenReturn(Optional.of(dest));
        lenient().when(destinationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        stubOwnership();

        service.updateDestination(projectId, sourceId, destId, IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .build());

        assertThat(dest.getTransformationId()).isEqualTo(attached);
    }

    private static EncryptionKeyRegistry createTestRegistry(String key, String salt) throws Exception {
        EncryptionKeyRegistry registry = new EncryptionKeyRegistry();
        setField(registry, "singleKey", key);
        setField(registry, "multiKeys", "");
        setField(registry, "configuredActiveVersion", 0);
        setField(registry, "salt", salt);
        Method init = registry.getClass().getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(registry);
        return registry;
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    // A malformed ladder is rejected at write time rather than replaced by the worker.

    @Test
    void createDestination_malformedRetryDelays_throwsWithActionableMessage() {
        stubOwnership();

        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .retryDelays("60,oops,900")
                .build();

        assertThatThrownBy(() -> service.createDestination(projectId, sourceId, request))
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

        assertThatThrownBy(() -> service.createDestination(projectId, sourceId, request))
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

        service.createDestination(projectId, sourceId, request);

        ArgumentCaptor<IncomingDestination> saved = ArgumentCaptor.forClass(IncomingDestination.class);
        verify(destinationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRetryDelays()).isEqualTo(RetryLadderDefaults.INCOMING_DELAYS);
        assertThat(saved.getValue().getMaxAttempts()).isEqualTo(RetryLadderDefaults.INCOMING_MAX_ATTEMPTS);
    }

    @Test
    void incomingDefaultLadder_deliberatelyShorterThanOutgoing() {
        // The two directions differ on purpose; aligning them is not a tidy-up.
        assertThat(RetryLadderDefaults.INCOMING_DELAYS).isNotEqualTo(RetryLadderDefaults.OUTGOING_DELAYS);
        assertThat(RetryLadderDefaults.INCOMING_MAX_ATTEMPTS).isLessThan(RetryLadderDefaults.OUTGOING_MAX_ATTEMPTS);
    }

    // A ladder longer than the escalation cap sent the Forward to the DLQ before its later tiers ran.
    @Test
    void createDestination_ladderOutlivingTheEscalationCap_throws() {
        stubOwnership();

        lenient().when(destinationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .retryDelays("21600")
                .maxAttempts(5)
                .build();

        assertThatThrownBy(() -> service.createDestination(projectId, sourceId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("24h");

        verify(destinationRepository, never()).saveAndFlush(any(IncomingDestination.class));
    }

    @Test
    void updateDestination_lengtheningTheLadderPastTheEscalationCap_throws() {
        IncomingDestination existing = buildDest();
        when(destinationRepository.findByIdAndIncomingSourceId(destId, sourceId)).thenReturn(Optional.of(existing));
        lenient().when(destinationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        stubOwnership();

        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/hook")
                .retryDelays("86400")
                .build();

        assertThatThrownBy(() -> service.updateDestination(projectId, sourceId, destId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryDelays");

        verify(destinationRepository, never()).saveAndFlush(any(IncomingDestination.class));
    }
}
