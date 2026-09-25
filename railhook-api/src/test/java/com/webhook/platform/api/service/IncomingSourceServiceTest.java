package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.IncomingSourceRequest;
import com.webhook.platform.api.dto.IncomingSourceResponse;
import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
import com.webhook.platform.api.service.verification.WebhookVerifierFactory;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncomingSourceServiceTest {

    @Mock
    private IncomingSourceRepository sourceRepository;
    @Mock
    private ProjectRepository projectRepository;

    private IncomingSourceService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();

    private Project project;

    @BeforeEach
    void setUp() throws Exception {
        EncryptionKeyRegistry registry = createTestRegistry(
                "test_encryption_key_32_chars_pad", "test_salt");
        service = new IncomingSourceService(
                sourceRepository, projectRepository,
                registry,
                new WebhookVerifierFactory("http://localhost:8080"),
                "http://localhost:8080"
        );
        project = Project.builder()
                .id(projectId)
                .organizationId(orgId)
                .name("Test Project")
                .build();
    }

    private IncomingSource buildSource() {
        return IncomingSource.builder()
                .id(sourceId)
                .projectId(projectId)
                .name("GitHub Webhooks")
                .slug("github-webhooks")
                .providerType(ProviderType.GITHUB)
                .status(IncomingSourceStatus.ACTIVE)
                .ingressPathToken("abc123token")
                .verificationMode(VerificationMode.NONE)
                .hmacHeaderName("X-Signature")
                .hmacSignaturePrefix("")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void createSource_storesTheHmacSecretEncrypted() {
        stubSave();

        IncomingSourceResponse response = service.createSource(projectId, IncomingSourceRequest.builder()
                .name("GitHub Webhooks")
                .providerType(ProviderType.GITHUB)
                .verificationMode(VerificationMode.HMAC_GENERIC)
                .hmacSecret("my-secret")
                .hmacHeaderName("X-Hub-Signature-256")
                .build());

        assertThat(response.isHmacSecretConfigured()).isTrue();
        ArgumentCaptor<IncomingSource> captor = ArgumentCaptor.forClass(IncomingSource.class);
        verify(sourceRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getHmacSecretEncrypted()).isNotNull().doesNotContain("my-secret");
        assertThat(captor.getValue().getHmacSecretIv()).isNotNull();
    }

    // A secret with no mode once saved as NONE, so a Stripe source accepted forged webhooks in production.
    @Test
    void createSource_secretWithoutMode_verifiesWithTheProviderPreset() {
        stubSave();

        IncomingSourceResponse response = service.createSource(projectId, IncomingSourceRequest.builder()
                .name("Stripe").providerType(ProviderType.STRIPE).hmacSecret("whsec_test").build());

        assertThat(response.getVerificationMode()).isEqualTo(VerificationMode.PROVIDER);
    }

    @Test
    void createSource_secretWithoutMode_onGenericVerifiesWithHmac() {
        stubSave();

        IncomingSourceResponse response = service.createSource(projectId, IncomingSourceRequest.builder()
                .name("Custom").providerType(ProviderType.GENERIC).hmacSecret("shared-secret").build());

        assertThat(response.getVerificationMode()).isEqualTo(VerificationMode.HMAC_GENERIC);
    }

    private void stubSave() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(sourceRepository.existsByProjectIdAndSlug(eq(projectId), anyString())).thenReturn(false);
        when(sourceRepository.existsByIngressPathToken(anyString())).thenReturn(false);
        when(sourceRepository.saveAndFlush(any(IncomingSource.class))).thenAnswer(inv -> {
            IncomingSource s = inv.getArgument(0);
            s.setId(sourceId);
            s.setCreatedAt(Instant.now());
            s.setUpdatedAt(Instant.now());
            return s;
        });
    }

    @Test
    void createSource_duplicateSlug_throws() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(sourceRepository.existsByProjectIdAndSlug(projectId, "github-webhooks")).thenReturn(true);

        IncomingSourceRequest request = IncomingSourceRequest.builder()
                .name("GitHub Webhooks")
                .slug("github-webhooks")
                .build();

        assertThatThrownBy(() -> service.createSource(projectId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void deleteSource_softDeletes() {
        IncomingSource source = buildSource();
        when(sourceRepository.findByIdAndProjectId(sourceId, projectId)).thenReturn(Optional.of(source));

        service.deleteSource(projectId, sourceId);

        assertThat(source.getStatus()).isEqualTo(IncomingSourceStatus.DISABLED);
        verify(sourceRepository).save(source);
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

    // A source that saved fine but could not verify only failed once the provider was sending.

    @Test
    void createRejectsProviderModeForAProviderWithNoVerifier() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        IncomingSourceRequest request = new IncomingSourceRequest();
        request.setName("Some provider");
        request.setProviderType(ProviderType.GENERIC);
        request.setVerificationMode(VerificationMode.PROVIDER);

        assertThatThrownBy(() -> service.createSource(projectId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no built-in verifier");

        verify(sourceRepository, never()).saveAndFlush(any());
    }

    @Test
    void createAcceptsProviderModeForEveryProviderThatHasAVerifier() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(sourceRepository.existsByProjectIdAndSlug(any(), any())).thenReturn(false);
        when(sourceRepository.existsByIngressPathToken(any())).thenReturn(false);
        when(sourceRepository.saveAndFlush(any(IncomingSource.class))).thenAnswer(inv -> inv.getArgument(0));

        for (ProviderType provider : new ProviderType[] {
                ProviderType.STRIPE, ProviderType.GITHUB, ProviderType.GITLAB,
                ProviderType.SLACK, ProviderType.SHOPIFY, ProviderType.TWILIO }) {
            IncomingSourceRequest request = new IncomingSourceRequest();
            request.setName("Source " + provider);
            request.setProviderType(provider);
            request.setVerificationMode(VerificationMode.PROVIDER);

            assertThatCode(() -> service.createSource(projectId, request))
                    .as("%s ships a verifier and must be accepted", provider)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void createRejectsGenericHmacWithoutTheSecretItSignsWith() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        IncomingSourceRequest request = new IncomingSourceRequest();
        request.setName("Some provider");
        request.setVerificationMode(VerificationMode.HMAC_GENERIC);
        request.setHmacHeaderName("X-Provider-Signature");

        assertThatThrownBy(() -> service.createSource(projectId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hmacSecret");
    }

    @Test
    void updateIsJudgedOnTheResultingRowNotTheRequest() {
        IncomingSource existing = buildSource();
        existing.setProviderType(ProviderType.GENERIC);
        existing.setVerificationMode(VerificationMode.NONE);
        when(sourceRepository.findByIdAndProjectId(sourceId, projectId)).thenReturn(Optional.of(existing));

        IncomingSourceRequest request = new IncomingSourceRequest();
        request.setVerificationMode(VerificationMode.PROVIDER);

        assertThatThrownBy(() -> service.updateSource(projectId, sourceId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no built-in verifier");
    }

}
