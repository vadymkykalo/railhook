package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.domain.entity.TunnelSession;
import com.webhook.platform.api.domain.enums.TunnelStatus;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.TunnelSessionRepository;
import com.webhook.platform.api.dto.TunnelSessionResponse;
import com.webhook.platform.api.service.billing.EntitlementService;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TunnelService {

    private final TunnelSessionRepository tunnelSessionRepository;
    private final ProjectRepository projectRepository;
    private final OrganizationRepository organizationRepository;
    private final EntitlementService entitlementService;
    private final RedisTunnelCoordinator redisTunnelCoordinator;

    @Value("${webhook.ingress-base-url:http://localhost:8080}")
    private String ingressBaseUrl;

    @Value("${tunnel.heartbeat-timeout-seconds:120}")
    private int heartbeatTimeoutSeconds;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Goes in a URL and is read aloud, so lower case and no punctuation.
    private static final String SLUG_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SLUG_LENGTH = 12;

    @Transactional
    public TunnelSession createSession(UUID userId, UUID projectId,
                                       int localPort, String clientInfo) {
        UUID organizationId = TenantContext.require();
        if (projectId != null) {
            projectRepository.findById(projectId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Project not found"));
        }
        enforceActiveTunnelLimit(organizationId);

        String tunnelToken = generateSecureToken();
        String publicSlug = slug(SECURE_RANDOM);

        TunnelSession session = TunnelSession.builder()
                .userId(userId)
                .organizationId(organizationId)
                .projectId(projectId)
                .tunnelToken(tunnelToken)
                .publicSlug(publicSlug)
                .localPort(localPort)
                .status(TunnelStatus.ACTIVE)
                .lastHeartbeat(Instant.now())
                .clientInfo(clientInfo)
                .build();

        session = tunnelSessionRepository.save(session);
        log.info("Tunnel session created: id={}, slug={}, user={}, org={}",
                session.getId(), publicSlug, userId, organizationId);
        return session;
    }

    // Under the organization lock, or two concurrent opens both pass a one-tunnel limit.
    private void enforceActiveTunnelLimit(UUID organizationId) {
        if (!entitlementService.isBillingEnabled()) {
            return;
        }
        organizationRepository.lockById(organizationId);
        entitlementService.checkTunnelLimit();
    }

    @Transactional
    public void closeSession(String tunnelToken) {
        tunnelSessionRepository.findByTunnelToken(tunnelToken).ifPresent(this::close);
    }

    @Transactional
    public void closeSession(UUID sessionId) {
        TunnelSession session = tunnelSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tunnel session not found"));
        close(session);
    }

    // Revoking sessions does not reach a tunnel: the CLI only presents the tunnel token.
    @Transactional
    public void closeSessionsOfUser(UUID userId) {
        TenantContext.require();
        if (TenantContext.isSystem()) {
            // Unscoped, the same lookup would close the user's tunnels in every organization.
            throw new IllegalStateException("Closing a member's tunnels needs the organization's scope");
        }
        tunnelSessionRepository.findByUserIdAndStatus(userId, TunnelStatus.ACTIVE).forEach(this::close);
    }

    // tunnel_sessions has no foreign key to organizations, so deleting one leaves its tunnels forwarding.
    @Transactional
    public void closeAllSessions() {
        TenantContext.require();
        if (TenantContext.isSystem()) {
            throw new IllegalStateException("Closing an organization's tunnels needs the organization's scope");
        }
        tunnelSessionRepository.findByStatus(TunnelStatus.ACTIVE).forEach(this::close);
    }

    @SystemTenant("an erasure deletes organizations other than the request's own, read off membership rows")
    @Transactional
    public void closeSessionsOfOrganization(UUID organizationId) {
        tunnelSessionRepository.findByOrganizationIdAndStatus(organizationId, TunnelStatus.ACTIVE)
                .forEach(this::close);
    }

    @SystemTenant("an erased person's tunnels are in every organization they belonged to")
    @Transactional
    public void closeAllSessionsOfUserEverywhere(UUID userId) {
        tunnelSessionRepository.findByUserIdAndStatus(userId, TunnelStatus.ACTIVE).forEach(this::close);
    }

    // Disconnects after commit: a CLI reconnecting before it would still read ACTIVE.
    private void close(TunnelSession session) {
        session.setStatus(TunnelStatus.CLOSED);
        session.setClosedAt(Instant.now());
        tunnelSessionRepository.save(session);
        log.info("Tunnel session closed: id={}, slug={}", session.getId(), session.getPublicSlug());

        String slug = session.getPublicSlug();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            redisTunnelCoordinator.disconnect(slug);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisTunnelCoordinator.disconnect(slug);
            }
        });
    }

    @Transactional
    public void heartbeat(String tunnelToken) {
        tunnelSessionRepository.findByTunnelToken(tunnelToken).ifPresent(session -> {
            session.setLastHeartbeat(Instant.now());
            tunnelSessionRepository.save(session);
        });
    }

    public TunnelSession getActiveBySlug(String slug) {
        TunnelSession session = tunnelSessionRepository.findByPublicSlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tunnel not found"));
        if (session.getStatus() != TunnelStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.GONE, "Tunnel is no longer active");
        }
        return session;
    }

    public TunnelSession getBySessionAndOrg(UUID sessionId) {
        return tunnelSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tunnel session not found"));
    }

    public TunnelSession getByToken(String tunnelToken) {
        return tunnelSessionRepository.findByTunnelToken(tunnelToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tunnel session not found"));
    }

    public List<TunnelSessionResponse> listActive() {
        UUID organizationId = TenantContext.require();
        return tunnelSessionRepository.findByOrganizationIdAndStatus(organizationId, TunnelStatus.ACTIVE)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<TunnelSessionResponse> listActiveByProject(UUID projectId) {
        UUID organizationId = TenantContext.require();
        return tunnelSessionRepository.findByOrganizationIdAndProjectIdAndStatus(organizationId, projectId, TunnelStatus.ACTIVE)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<TunnelSessionResponse> listActiveByUser(UUID userId) {
        return tunnelSessionRepository.findByUserIdAndStatus(userId, TunnelStatus.ACTIVE)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public TunnelSessionResponse toResponse(TunnelSession session) {
        return TunnelSessionResponse.builder()
                .id(session.getId())
                .organizationId(session.getOrganizationId())
                .userId(session.getUserId())
                .projectId(session.getProjectId())
                .publicSlug(session.getPublicSlug())
                .publicUrl(buildPublicUrl(session.getPublicSlug()))
                .localPort(session.getLocalPort())
                .status(session.getStatus())
                .createdAt(session.getCreatedAt())
                .lastHeartbeat(session.getLastHeartbeat())
                .closedAt(session.getClosedAt())
                .clientInfo(session.getClientInfo())
                .build();
    }

    public String buildPublicUrl(String slug) {
        return ingressBaseUrl + "/tunnel/" + slug;
    }

    @SystemTenant
    @Scheduled(fixedDelayString = "${tunnel.cleanup-interval-ms:60000}")
    @Transactional
    public void cleanupStaleSessions() {
        Instant threshold = Instant.now().minus(heartbeatTimeoutSeconds, ChronoUnit.SECONDS);
        int expired = tunnelSessionRepository.expireStale(threshold, Instant.now());
        if (expired > 0) {
            log.info("Expired {} stale tunnel sessions", expired);
        }
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[48];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Drawn per character: trimmed base64 threw when it held too many - and _.
    static String slug(Random random) {
        StringBuilder slug = new StringBuilder("tun-");
        for (int i = 0; i < SLUG_LENGTH; i++) {
            slug.append(SLUG_ALPHABET.charAt(random.nextInt(SLUG_ALPHABET.length())));
        }
        return slug.toString();
    }
}
