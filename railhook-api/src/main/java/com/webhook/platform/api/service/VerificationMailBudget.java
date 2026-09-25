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
 * Caps mail to unproven addresses so an account cannot flood a stranger's inbox. In Postgres,
 * because a daily cap that a Redis restart resets is not a cap.
 */
@Service
@Slf4j
public class VerificationMailBudget {

    static final int MAX_SENDS_PER_DAY = 5;
    static final int MAX_CHANGES_PER_DAY = 3;
    private static final Duration WINDOW = Duration.ofHours(24);
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
