package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.ApiKey;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.repository.ApiKeyRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.ApiKeyResponse;
import com.webhook.platform.api.dto.ApiKeyRotateRequest;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.util.CryptoUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyService.rotateApiKey — the rotation grace window")
class ApiKeyRotationTest {

    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private ProjectRepository projectRepository;

    private ApiKeyService service;
    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID apiKeyId = UUID.randomUUID();
    private final List<ApiKey> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ApiKeyService(apiKeyRepository, projectRepository);
        TenantContext.set(organizationId);
        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(Project.builder().id(projectId).name("p").build()));
        lenient().when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(invocation -> {
            ApiKey key = invocation.getArgument(0);
            if (key.getId() == null) {
                key.setId(UUID.randomUUID());
            }
            saved.add(key);
            return key;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ApiKey liveKey() {
        return ApiKey.builder()
                .id(apiKeyId)
                .organizationId(organizationId)
                .projectId(projectId)
                .name("production ingest")
                .keyHash(CryptoUtils.hashApiKey("the-key-the-customer-deployed"))
                .keyPrefix("the-key-")
                .scope(ApiKeyScope.READ_ONLY)
                .build();
    }

    @Test
    @DisplayName("the outgoing key keeps working for the grace window, and the new one is live now")
    void bothKeysAuthenticateDuringTheWindow() {
        ApiKey retiring = liveKey();
        when(apiKeyRepository.findByIdAndProjectId(apiKeyId, projectId)).thenReturn(Optional.of(retiring));

        Instant before = Instant.now();
        ApiKeyResponse replacement = service.rotateApiKey(projectId, apiKeyId,
                ApiKeyRotateRequest.builder().gracePeriodHours(24).build());

        assertThat(replacement.getKey())
                .as("the plaintext is returned exactly once, at rotation")
                .isNotBlank();
        assertThat(replacement.getId()).isNotEqualTo(apiKeyId);

        assertThat(retiring.getRevokedAt()).isNull();
        assertThat(retiring.getExpiresAt())
                .isNotNull()
                .isAfter(before.plus(Duration.ofHours(23)))
                .isBefore(before.plus(Duration.ofHours(25)));
        assertThat(retiring.getRotatedAt()).isNotNull();
        assertThat(retiring.getReplacedById()).isEqualTo(replacement.getId());
    }

    @Test
    @DisplayName("the replacement inherits the scope, so a rotation never widens what a key may do")
    void replacementInheritsScope() {
        ApiKey retiring = liveKey();
        when(apiKeyRepository.findByIdAndProjectId(apiKeyId, projectId)).thenReturn(Optional.of(retiring));

        service.rotateApiKey(projectId, apiKeyId, null);

        ApiKey replacement = saved.get(0);
        assertThat(replacement.getScope())
                .as("a READ_ONLY key must not come back READ_WRITE because someone rotated it")
                .isEqualTo(ApiKeyScope.READ_ONLY);
        assertThat(replacement.getName()).isEqualTo("production ingest");
        assertThat(replacement.getKeyHash()).isNotEqualTo(retiring.getKeyHash());
    }

    @Test
    @DisplayName("with no request body at all the window is a day, not zero and not forever")
    void defaultsToADay() {
        ApiKey retiring = liveKey();
        when(apiKeyRepository.findByIdAndProjectId(apiKeyId, projectId)).thenReturn(Optional.of(retiring));

        Instant before = Instant.now();
        service.rotateApiKey(projectId, apiKeyId, null);

        assertThat(retiring.getExpiresAt())
                .isAfter(before.plus(Duration.ofHours(23)))
                .isBefore(before.plus(Duration.ofHours(25)));
    }

    @Test
    @DisplayName("a zero window cuts the old key off now — the rotation you do after a leak")
    void zeroWindowRetiresImmediately() {
        ApiKey retiring = liveKey();
        when(apiKeyRepository.findByIdAndProjectId(apiKeyId, projectId)).thenReturn(Optional.of(retiring));

        service.rotateApiKey(projectId, apiKeyId,
                ApiKeyRotateRequest.builder().gracePeriodHours(0).build());

        assertThat(retiring.getExpiresAt())
                .as("expired as of now, so the very next request with it is refused")
                .isBeforeOrEqualTo(Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("rotating never extends the life of the key being rotated away")
    void anEarlierExpiryIsNotPushedOut() {
        ApiKey retiring = liveKey();
        Instant expiresInAnHour = Instant.now().plus(Duration.ofHours(1));
        retiring.setExpiresAt(expiresInAnHour);
        when(apiKeyRepository.findByIdAndProjectId(apiKeyId, projectId)).thenReturn(Optional.of(retiring));

        service.rotateApiKey(projectId, apiKeyId,
                ApiKeyRotateRequest.builder().gracePeriodHours(48).build());

        assertThat(retiring.getExpiresAt())
                .as("a 48-hour window must not resurrect a key the customer set to die in one")
                .isEqualTo(expiresInAnHour);
    }

    @Test
    @DisplayName("a revoked key cannot be rotated — there is nothing live to keep alive")
    void revokedKeyCannotBeRotated() {
        ApiKey revoked = liveKey();
        revoked.setRevokedAt(Instant.now().minusSeconds(60));
        when(apiKeyRepository.findByIdAndProjectId(apiKeyId, projectId)).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.rotateApiKey(projectId, apiKeyId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    @DisplayName("rotating the same key twice is refused rather than orphaning the first replacement")
    void doubleRotationIsRefused() {
        ApiKey alreadyRotated = liveKey();
        alreadyRotated.setRotatedAt(Instant.now().minusSeconds(10));
        alreadyRotated.setReplacedById(UUID.randomUUID());
        when(apiKeyRepository.findByIdAndProjectId(apiKeyId, projectId)).thenReturn(Optional.of(alreadyRotated));

        assertThatThrownBy(() -> service.rotateApiKey(projectId, apiKeyId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been rotated");
    }

    @Test
    @DisplayName("the listing tells a retiring key apart from one the customer gave an expiry")
    void listingDistinguishesRetiringFromExpiring() {
        ApiKey retiring = liveKey();
        when(apiKeyRepository.findByIdAndProjectId(apiKeyId, projectId)).thenReturn(Optional.of(retiring));
        service.rotateApiKey(projectId, apiKeyId, null);

        when(apiKeyRepository.findByProjectIdAndRevokedAtIsNull(projectId)).thenReturn(List.of(retiring));

        ApiKeyResponse listed = service.listApiKeys(projectId).get(0);
        assertThat(listed.getRotatedAt()).isNotNull();
        assertThat(listed.getReplacedById()).isNotNull();
        assertThat(listed.getKey()).as("never the plaintext, outside the one response that mints it").isNull();
    }

}
