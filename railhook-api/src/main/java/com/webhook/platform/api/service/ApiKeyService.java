package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.ApiKey;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.repository.ApiKeyRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.ApiKeyRequest;
import com.webhook.platform.api.dto.ApiKeyResponse;
import com.webhook.platform.api.dto.ApiKeyRotateRequest;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.util.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.webhook.platform.api.exception.NotFoundException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final ProjectRepository projectRepository;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int API_KEY_LENGTH = 32;

    /**
     * How long an outgoing key keeps working when the caller does not say. A working day: long
     * enough for a deployment to reach every instance, short enough that nobody forgets a second
     * live credential exists.
     */
    private static final int DEFAULT_GRACE_PERIOD_HOURS = 24;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, ProjectRepository projectRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.projectRepository = projectRepository;
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "ApiKey")
    @Transactional
    public ApiKeyResponse createApiKey(UUID projectId, ApiKeyRequest request) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));

        String plainKey = generateApiKey();
        String keyHash = CryptoUtils.hashApiKey(plainKey);
        String keyPrefix = plainKey.substring(0, Math.min(8, plainKey.length()));

        ApiKey apiKey = ApiKey.builder()
                .projectId(projectId)
                .name(request.getName())
                .keyHash(keyHash)
                .keyPrefix(keyPrefix)
                .scope(request.getScope() != null ? request.getScope() : ApiKeyScope.READ_WRITE)
                .expiresAt(request.getExpiresAt())
                .build();

        apiKey = apiKeyRepository.save(apiKey);
        log.info("Created API key {} for project {}", apiKey.getId(), projectId);

        return mapToResponse(apiKey, plainKey);
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> listApiKeys(UUID projectId) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));

        List<ApiKey> apiKeys = apiKeyRepository.findByProjectIdAndRevokedAtIsNull(projectId);
        return apiKeys.stream()
                .map(key -> mapToResponse(key, null))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<ApiKeyResponse> listApiKeys(UUID projectId, Pageable pageable) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));

        return apiKeyRepository.findByProjectIdAndRevokedAtIsNull(projectId, pageable)
                .map(key -> mapToResponse(key, null));
    }

    @Auditable(action = AuditAction.REVOKE, resourceType = "ApiKey")
    @Transactional
    public void revokeApiKey(UUID projectId, UUID apiKeyId) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));

        ApiKey apiKey = apiKeyRepository.findByIdAndProjectId(apiKeyId, projectId)
                .orElseThrow(() -> new NotFoundException("API key not found"));

        if (apiKey.getRevokedAt() != null) {
            throw new IllegalArgumentException("API key is already revoked");
        }

        apiKey.setRevokedAt(Instant.now());
        apiKeyRepository.save(apiKey);
        log.info("Revoked API key {} for project {}", apiKeyId, projectId);
    }


    /**
     * Replaces a key with a new one, leaving the old one working for a grace window.
     *
     * <p>The gap this closes is the same one {@code EndpointService.rotateSecret} closed for
     * signing secrets, and it is worth stating in the same terms. With only create and revoke,
     * rolling a key over is a race the customer has to run themselves: create the new key, deploy
     * it everywhere, and revoke the old one at exactly the right moment — too early and requests
     * fail with 401 until the deploy finishes, too late and a credential they meant to retire is
     * still live and now forgotten. Neither end of that is something an API should make somebody
     * orchestrate by hand.
     *
     * <p>So both keys authenticate for the length of the window. The outgoing key is given an
     * expiry rather than being revoked, because {@code ApiKeyAuthenticationFilter} already honours
     * {@code expires_at} on every request — the window needs no new enforcement path, only a date.
     * {@code rotated_at} is what distinguishes this expiry from one the customer asked for, so a
     * key in its grace window can be shown as retiring rather than as merely expiring.
     *
     * <p>A window of zero is allowed and means "cut it off now": that is the rotation somebody
     * performs after a leak, where the point is precisely that the old key stops working.
     *
     * <p>An expiry already sooner than the window is never pushed out. Rotating a key must not be
     * a way to extend the life of the key being rotated away.
     */
    @Auditable(action = AuditAction.ROTATE_SECRET, resourceType = "ApiKey")
    @Transactional
    public ApiKeyResponse rotateApiKey(UUID projectId, UUID apiKeyId, ApiKeyRotateRequest request) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));

        ApiKey retiring = apiKeyRepository.findByIdAndProjectId(apiKeyId, projectId)
                .orElseThrow(() -> new NotFoundException("API key not found"));

        if (retiring.getRevokedAt() != null) {
            throw new IllegalArgumentException(
                    "API key is revoked; create a new key rather than rotating a dead one");
        }
        if (retiring.getRotatedAt() != null) {
            // Otherwise a second rotation would silently orphan the key created by the first,
            // which is live, unnamed in any successor chain, and about to be forgotten.
            throw new IllegalArgumentException(
                    "API key has already been rotated; rotate its replacement instead");
        }

        String plainKey = generateApiKey();
        ApiKey replacement = apiKeyRepository.save(ApiKey.builder()
                .projectId(projectId)
                .name(retiring.getName())
                .keyHash(CryptoUtils.hashApiKey(plainKey))
                .keyPrefix(plainKey.substring(0, Math.min(8, plainKey.length())))
                .scope(retiring.getScope())
                .expiresAt(request != null ? request.getExpiresAt() : null)
                .build());

        int graceHours = request != null && request.getGracePeriodHours() != null
                ? request.getGracePeriodHours()
                : DEFAULT_GRACE_PERIOD_HOURS;
        Instant windowCloses = Instant.now().plus(Duration.ofHours(graceHours));

        retiring.setRotatedAt(Instant.now());
        retiring.setReplacedById(replacement.getId());
        retiring.setExpiresAt(earlier(retiring.getExpiresAt(), windowCloses));
        apiKeyRepository.save(retiring);

        log.info("Rotated API key {} for project {}; replacement {} live, old key valid until {}",
                apiKeyId, projectId, replacement.getId(), retiring.getExpiresAt());

        return mapToResponse(replacement, plainKey);
    }

    /** The sooner of two expiries, treating "no expiry" as later than any date. */
    private static Instant earlier(Instant existing, Instant candidate) {
        if (existing == null) {
            return candidate;
        }
        return existing.isBefore(candidate) ? existing : candidate;
    }

    private String generateApiKey() {
        byte[] randomBytes = new byte[API_KEY_LENGTH];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private ApiKeyResponse mapToResponse(ApiKey apiKey, String plainKey) {
        return ApiKeyResponse.builder()
                .id(apiKey.getId())
                .projectId(apiKey.getProjectId())
                .name(apiKey.getName())
                .keyPrefix(apiKey.getKeyPrefix())
                .lastUsedAt(apiKey.getLastUsedAt())
                .createdAt(apiKey.getCreatedAt())
                .revokedAt(apiKey.getRevokedAt())
                .expiresAt(apiKey.getExpiresAt())
                .scope(apiKey.getScope() != null ? apiKey.getScope().name() : "READ_WRITE")
                .key(plainKey)
                .rotatedAt(apiKey.getRotatedAt())
                .replacedById(apiKey.getReplacedById())
                .build();
    }
}
