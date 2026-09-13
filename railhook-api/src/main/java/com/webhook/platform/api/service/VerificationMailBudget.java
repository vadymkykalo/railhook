package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.AuditLogAspect;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.entity.VerificationEmailSend;
import com.webhook.platform.api.domain.repository.EmailChangeRequestRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.VerificationEmailSendRepository;
import com.webhook.platform.api.tenancy.SystemTenant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How much mail one account can cause to be sent to addresses it has not proved it owns.
 *
 * <p>Verification and email-change mail go to an address the requester typed. Without a cap, an
 * account is a way to send a stranger's inbox as many "confirm your email" messages as a script
 * cares to, from the deployment's own reputation. So an account gets {@value #MAX_SENDS_PER_DAY}
 * such mails a day across registration, resend and change, and {@value #MAX_CHANGES_PER_DAY}
 * address changes — counted in Postgres, because a day-long allowance that a Redis restart resets
 * is not a cap.
 *
 * <p>Also the one place these flows write to the audit log, so each entry lands in every
 * organization the person belongs to — which is where an owner looks — and so a refusal is
 * recorded even though the request that hit it rolls back.
 */
@Service
@Slf4j
public class VerificationMailBudget {

    static final int MAX_SENDS_PER_DAY = 5;
    static final int MAX_CHANGES_PER_DAY = 3;
    private static final Duration WINDOW = Duration.ofHours(24);
    /** Settled rows are kept a while past the window, then swept on the next write. */
    private static final Duration RETENTION = Duration.ofDays(7);

    private final VerificationEmailSendRepository sendRepository;
    private final EmailChangeRequestRepository changeRepository;
    private final MembershipRepository membershipRepository;
    private final AuditLogAspect auditLog;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VerificationMailBudget(VerificationEmailSendRepository sendRepository,
                                  EmailChangeRequestRepository changeRepository,
                                  MembershipRepository membershipRepository,
                                  AuditLogAspect auditLog) {
        this.sendRepository = sendRepository;
        this.changeRepository = changeRepository;
        this.membershipRepository = membershipRepository;
        this.auditLog = auditLog;
    }

    @SystemTenant("counts one person's mail, which is not confined to any organization they belong to")
    public void requireSendAllowance(User user) {
        long sent = sendRepository.countByUserIdAndCreatedAtAfter(user.getId(), Instant.now().minus(WINDOW));
        if (sent >= MAX_SENDS_PER_DAY) {
            refuse(user.getId(), "verification-sends",
                    "Too many verification emails today. Try again tomorrow.");
        }
    }

    @SystemTenant("counts one person's address changes, which are not confined to any organization they belong to")
    public void requireAddressChangeAllowance(User user) {
        long changes = changeRepository.countByUserIdAndCreatedAtAfter(user.getId(), Instant.now().minus(WINDOW));
        if (changes >= MAX_CHANGES_PER_DAY) {
            refuse(user.getId(), "address-changes", "Too many email changes today. Try again tomorrow.");
        }
    }

    @SystemTenant("records one person's mail, which is not confined to any organization they belong to")
    public void recordSend(UUID userId, String reason) {
        Instant now = Instant.now();
        sendRepository.deleteBefore(now.minus(RETENTION));
        changeRepository.deleteSettledBefore(now.minus(RETENTION));
        sendRepository.save(VerificationEmailSend.builder().userId(userId).reason(reason).build());
    }

    /** A per-address or per-IP limiter said no before any of the above was asked. */
    @SystemTenant("audits against every organization the person belongs to")
    public void recordRateLimited(UUID userId, String limit) {
        audit(userId, AuditAction.EMAIL_RATE_LIMITED, "FAILURE", "Rate limit: " + limit, Map.of("limit", limit));
    }

    @SystemTenant("audits against every organization the person belongs to")
    public void audit(UUID userId, AuditAction action, Map<String, Object> details) {
        audit(userId, action, "SUCCESS", null, details);
    }

    private void refuse(UUID userId, String limit, String message) {
        log.warn("Refused {} for user {}: daily cap reached", limit, userId);
        audit(userId, AuditAction.EMAIL_RATE_LIMITED, "FAILURE", message, Map.of("limit", limit));
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    private void audit(UUID userId, AuditAction action, String status, String error, Map<String, Object> details) {
        String json;
        try {
            json = objectMapper.writeValueAsString(new LinkedHashMap<>(details));
        } catch (Exception e) {
            json = null;
        }
        List<UUID> organizations = membershipRepository.findByUserId(userId).stream()
                .map(Membership::getOrganizationId)
                .distinct()
                .toList();
        if (organizations.isEmpty()) {
            auditLog.record(action, "Auth", userId, null, status, error, json);
            return;
        }
        for (UUID organization : organizations) {
            auditLog.record(action, "Auth", userId, organization, status, error, json);
        }
    }
}
