package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.Consumer;
import com.webhook.platform.api.domain.entity.PortalSession;
import com.webhook.platform.api.domain.repository.PortalSessionRepository;
import com.webhook.platform.api.dto.PortalSessionRequest;
import com.webhook.platform.api.dto.PortalSessionResponse;
import com.webhook.platform.api.security.PortalSessionAuthenticationToken;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.common.util.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Opens and ends portal sessions: the credential a customer's backend hands to one of its users'
 * browsers so the portal can act for that one Consumer.
 *
 * <p>The token is 32 random bytes behind a recognisable prefix, returned once and stored only as
 * its SHA-256 — the same treatment as an API key, and unlike a shared debug link, whose token is a
 * read-only view of one event. This one can register Endpoints and retry Deliveries.
 */
@Slf4j
@Service
public class PortalSessionService {

    static final int DEFAULT_TTL_MINUTES = 60;

    private final PortalSessionRepository portalSessionRepository;
    private final ConsumerService consumerService;
    private final Clock clock;
    private final String baseUrl;

    public PortalSessionService(PortalSessionRepository portalSessionRepository,
                                ConsumerService consumerService,
                                Clock clock,
                                @Value("${app.base-url:http://localhost:5173}") String baseUrl) {
        this.portalSessionRepository = portalSessionRepository;
        this.consumerService = consumerService;
        this.clock = clock;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "PortalSession")
    @Transactional
    public PortalSessionResponse createSession(UUID projectId, UUID consumerId, PortalSessionRequest request) {
        Consumer consumer = consumerService.requireConsumer(projectId, consumerId);
        int ttlMinutes = request != null && request.getTtlMinutes() != null
                ? request.getTtlMinutes() : DEFAULT_TTL_MINUTES;
        String allowedOrigin = request == null ? null : blankToNull(request.getAllowedOrigin());

        String token = PortalSessionAuthenticationToken.TOKEN_PREFIX + CryptoUtils.generateSecureToken(32);
        PortalSession session = portalSessionRepository.saveAndFlush(PortalSession.builder()
                .projectId(projectId)
                .consumerId(consumer.getId())
                .tokenHash(CryptoUtils.hashApiKey(token))
                .allowedOrigin(allowedOrigin)
                .expiresAt(clock.instant().plus(Duration.ofMinutes(ttlMinutes)))
                .build());

        return PortalSessionResponse.builder()
                .id(session.getId())
                .consumerId(consumer.getId())
                .url(portalUrl(token, allowedOrigin))
                .token(token)
                .allowedOrigin(allowedOrigin)
                .expiresAt(session.getExpiresAt())
                .build();
    }

    /** Ends every open session of a Consumer — what to do when a token may have leaked. */
    @Auditable(action = AuditAction.REVOKE, resourceType = "PortalSession")
    @Transactional
    public void revokeSessions(UUID projectId, UUID consumerId) {
        Consumer consumer = consumerService.requireConsumer(projectId, consumerId);
        int revoked = portalSessionRepository.deleteByConsumerId(consumer.getId());
        log.info("Revoked {} portal sessions of consumer {}", revoked, consumerId);
    }

    /**
     * The portal's address. The token rides in the fragment, which a browser never sends to any
     * server — not to Railhook's nginx, not in a Referer — so it reaches the page's script and no
     * access log. The allowed origin rides in the query, where nginx turns it into the page's
     * {@code frame-ancestors}; see the portal location in the UI's nginx.conf.
     */
    private String portalUrl(String token, String allowedOrigin) {
        String query = allowedOrigin == null ? "" : "?origin=" + allowedOrigin;
        return baseUrl + "/portal" + query + "#" + token;
    }

    /**
     * Housekeeping only: an expired session authenticates nothing whether or not its row is still
     * here. Without it the table would grow by one row per portal page load, forever.
     */
    @SystemTenant("housekeeping over every organization's expired portal sessions")
    @Scheduled(fixedDelayString = "${portal.session-cleanup-interval-ms:3600000}")
    @SchedulerLock(name = "portalSessionCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void purgeExpiredSessions() {
        int deleted = portalSessionRepository.deleteExpiredBefore(clock.instant());
        if (deleted > 0) {
            log.debug("Purged {} expired portal sessions", deleted);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
