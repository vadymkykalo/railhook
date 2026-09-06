package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.IncomingSourceRequest;
import com.webhook.platform.api.dto.IncomingSourceResponse;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;
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
    void createSource_success() {
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

        IncomingSourceRequest request = IncomingSourceRequest.builder()
                .name("GitHub Webhooks")
                .slug("github-webhooks")
                .providerType(ProviderType.GITHUB)
                .verificationMode(VerificationMode.HMAC_GENERIC)
                .hmacSecret("my-secret")
                .hmacHeaderName("X-Hub-Signature-256")
                .hmacSignaturePrefix("sha256=")
                .build();

        IncomingSourceResponse response = service.createSource(projectId, request);

        assertThat(response.getId()).isEqualTo(sourceId);
        assertThat(response.getName()).isEqualTo("GitHub Webhooks");
        assertThat(response.getSlug()).isEqualTo("github-webhooks");
        assertThat(response.getProviderType()).isEqualTo(ProviderType.GITHUB);
        assertThat(response.getStatus()).isEqualTo(IncomingSourceStatus.ACTIVE);
        assertThat(response.getVerificationMode()).isEqualTo(VerificationMode.HMAC_GENERIC);
        assertThat(response.getHmacHeaderName()).isEqualTo("X-Hub-Signature-256");
        assertThat(response.getHmacSignaturePrefix()).isEqualTo("sha256=");
        assertThat(response.isHmacSecretConfigured()).isTrue();
        assertThat(response.getIngressUrl()).startsWith("http://localhost:8080/ingress/");

        ArgumentCaptor<IncomingSource> captor = ArgumentCaptor.forClass(IncomingSource.class);
        verify(sourceRepository).saveAndFlush(captor.capture());
        IncomingSource saved = captor.getValue();
        assertThat(saved.getHmacSecretEncrypted()).isNotNull();
        assertThat(saved.getHmacSecretIv()).isNotNull();
    }

    @Test
    void createSource_defaultValues() {
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

        IncomingSourceRequest request = IncomingSourceRequest.builder()
                .name("My Source")
                .build();

        IncomingSourceResponse response = service.createSource(projectId, request);

        assertThat(response.getProviderType()).isEqualTo(ProviderType.GENERIC);
        assertThat(response.getVerificationMode()).isEqualTo(VerificationMode.NONE);
        assertThat(response.isHmacSecretConfigured()).isFalse();
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
    void getSource_success() {
        IncomingSource source = buildSource();
        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        IncomingSourceResponse response = service.getSource(sourceId);

        assertThat(response.getId()).isEqualTo(sourceId);
        assertThat(response.getName()).isEqualTo("GitHub Webhooks");
    }

    @Test
    void getSource_notFound() {
        when(sourceRepository.findById(sourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSource(sourceId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void listSources_success() {
        IncomingSource source = buildSource();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(sourceRepository.findByProjectId(eq(projectId), any()))
                .thenReturn(new PageImpl<>(List.of(source)));

        Page<IncomingSourceResponse> page = service.listSources(projectId, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getName()).isEqualTo("GitHub Webhooks");
    }

    @Test
    void updateSource_success() {
        IncomingSource source = buildSource();
        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(sourceRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        IncomingSourceRequest request = IncomingSourceRequest.builder()
                .name("Updated Name")
                .providerType(ProviderType.STRIPE)
                .status(IncomingSourceStatus.DISABLED)
                .build();

        IncomingSourceResponse response = service.updateSource(sourceId, request);

        assertThat(response.getName()).isEqualTo("Updated Name");
        assertThat(response.getProviderType()).isEqualTo(ProviderType.STRIPE);
        assertThat(response.getStatus()).isEqualTo(IncomingSourceStatus.DISABLED);
    }

    @Test
    void deleteSource_softDeletes() {
        IncomingSource source = buildSource();
        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        service.deleteSource(sourceId);

        assertThat(source.getStatus()).isEqualTo(IncomingSourceStatus.DISABLED);
        verify(sourceRepository).save(source);
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

    // ── Refusing a source that would only fail once webhooks arrive ──────────

    @Test
    void createRejectsProviderModeForAProviderWithNoVerifier() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        IncomingSourceRequest request = new IncomingSourceRequest();
        request.setName("Some provider");
        request.setProviderType(ProviderType.GENERIC);
        request.setVerificationMode(VerificationMode.PROVIDER);

        /* GENERIC is the one ProviderType with no verifier, and deliberately so: it is the
           label for a provider with no preset, which HMAC_GENERIC mode is what verifies.
           This used to save happily and throw IllegalStateException at ingress — the source
           looked configured, and the failure showed up once the provider was already
           sending. */
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

        /* Not a crash like the PROVIDER case — every delivery would simply be stored with
           verified=false and "Verification error", and the cause would be a field nobody
           filled in. Cheaper to refuse at the keyboard. */
        assertThatThrownBy(() -> service.createSource(projectId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hmacSecret");
    }

    @Test
    void updateIsJudgedOnTheResultingRowNotTheRequest() {
        IncomingSource existing = buildSource();
        existing.setProviderType(ProviderType.GENERIC);
        existing.setVerificationMode(VerificationMode.NONE);
        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(existing));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        IncomingSourceRequest request = new IncomingSourceRequest();
        request.setVerificationMode(VerificationMode.PROVIDER);

        /* The request alone looks harmless — it names no provider. It is the combination
           with the provider already on the row that is unverifiable, which is why the check
           runs against the merged state. */
        assertThatThrownBy(() -> service.updateSource(sourceId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no built-in verifier");
    }

    @Test
    void noneModeAcceptsAnyProvider() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(sourceRepository.existsByProjectIdAndSlug(any(), any())).thenReturn(false);
        when(sourceRepository.existsByIngressPathToken(any())).thenReturn(false);
        when(sourceRepository.saveAndFlush(any(IncomingSource.class))).thenAnswer(inv -> inv.getArgument(0));

        IncomingSourceRequest request = new IncomingSourceRequest();
        request.setName("A provider that does not sign at all");
        request.setProviderType(ProviderType.GENERIC);
        request.setVerificationMode(VerificationMode.NONE);

        // Receiving from anything that can POST is the point; only PROVIDER mode makes a
        // promise about the provider.
        assertThatCode(() -> service.createSource(projectId, request)).doesNotThrowAnyException();
    }
}
