package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.domain.entity.AlertEvent;
import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.domain.entity.IncidentTimeline;
import com.webhook.platform.api.domain.enums.AlertChannel;
import com.webhook.platform.api.domain.enums.AlertSeverity;
import com.webhook.platform.api.domain.enums.AlertType;
import com.webhook.platform.api.domain.enums.IncidentStatus;
import com.webhook.platform.api.domain.enums.IncidentTimelineType;
import com.webhook.platform.api.domain.repository.AlertEventRepository;
import com.webhook.platform.api.domain.repository.AlertRuleRepository;
import com.webhook.platform.api.domain.repository.IncidentRepository;
import com.webhook.platform.api.domain.repository.IncidentTimelineRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.AlertEventResponse;
import com.webhook.platform.api.dto.AlertRuleRequest;
import com.webhook.platform.api.dto.AlertRuleResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.security.UrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class AlertService {

    private final AlertRuleRepository ruleRepository;
    private final AlertEventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final IncidentRepository incidentRepository;
    private final IncidentTimelineRepository timelineRepository;
    private final AlertNotificationService notificationService;
    private final boolean allowPrivateIps;
    private final List<String> allowedHosts;

    public AlertService(
            AlertRuleRepository ruleRepository,
            AlertEventRepository eventRepository,
            ProjectRepository projectRepository,
            IncidentRepository incidentRepository,
            IncidentTimelineRepository timelineRepository,
            AlertNotificationService notificationService,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") List<String> allowedHosts) {
        this.ruleRepository = ruleRepository;
        this.eventRepository = eventRepository;
        this.projectRepository = projectRepository;
        this.incidentRepository = incidentRepository;
        this.timelineRepository = timelineRepository;
        this.notificationService = notificationService;
        this.allowPrivateIps = allowPrivateIps;
        this.allowedHosts = allowedHosts;
    }

    /**
     * A rule's notification webhook is a URL this server fetches on the user's behalf, which
     * makes it an SSRF sink exactly like an Endpoint's url — and it was the one outbound URL in
     * the product that nothing validated. Blank is not a bad URL: updateRule reads it as "unset
     * this", and validating it would leave no way to remove a URL already stored.
     */
    private void validateNotificationUrl(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        UrlValidator.validateWebhookUrl(webhookUrl, allowPrivateIps, allowedHosts);
    }

    // ─── Rule CRUD ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AlertRuleResponse> listRules(UUID projectId) {
        validateProjectAccess(projectId);
        return ruleRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(this::toRuleResponse)
                .toList();
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "AlertRule")
    @Transactional
    public AlertRuleResponse createRule(UUID projectId, AlertRuleRequest request) {
        validateProjectAccess(projectId);
        validateNotificationUrl(request.getWebhookUrl());

        AlertRule rule = AlertRule.builder()
                .projectId(projectId)
                .name(request.getName())
                .description(request.getDescription())
                .alertType(request.getAlertType())
                .severity(request.getSeverity() != null ? request.getSeverity() : AlertSeverity.WARNING)
                .channel(request.getChannel() != null ? request.getChannel() : AlertChannel.IN_APP)
                .thresholdValue(request.getThresholdValue())
                .windowMinutes(request.getWindowMinutes() != null ? request.getWindowMinutes() : 5)
                .endpointId(request.getEndpointId())
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .muted(request.getMuted() != null ? request.getMuted() : false)
                .snoozedUntil(request.getSnoozedUntil())
                .webhookUrl(request.getWebhookUrl())
                .emailRecipients(request.getEmailRecipients())
                .build();

        rule = ruleRepository.save(rule);
        log.info("Created alert rule '{}' ({}) for project {}", rule.getName(), rule.getAlertType(), projectId);
        return toRuleResponse(rule);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "AlertRule")
    @Transactional
    public AlertRuleResponse updateRule(UUID projectId, UUID ruleId, AlertRuleRequest request) {
        validateProjectAccess(projectId);

        validateNotificationUrl(request.getWebhookUrl());

        AlertRule rule = ruleRepository.findByIdAndProjectId(ruleId, projectId)
                .orElseThrow(() -> new NotFoundException("Alert rule not found"));

        if (request.getName() != null) rule.setName(request.getName());
        if (request.getDescription() != null) rule.setDescription(request.getDescription());
        if (request.getAlertType() != null) rule.setAlertType(request.getAlertType());
        if (request.getSeverity() != null) rule.setSeverity(request.getSeverity());
        if (request.getChannel() != null) rule.setChannel(request.getChannel());
        if (request.getThresholdValue() != null) rule.setThresholdValue(request.getThresholdValue());
        if (request.getWindowMinutes() != null) rule.setWindowMinutes(request.getWindowMinutes());
        if (request.getEndpointId() != null) rule.setEndpointId(request.getEndpointId());
        if (request.getEnabled() != null) rule.setEnabled(request.getEnabled());
        if (request.getMuted() != null) rule.setMuted(request.getMuted());
        if (request.getSnoozedUntil() != null) rule.setSnoozedUntil(request.getSnoozedUntil());
        if (request.getWebhookUrl() != null) rule.setWebhookUrl(request.getWebhookUrl().isBlank() ? null : request.getWebhookUrl());
        if (request.getEmailRecipients() != null) rule.setEmailRecipients(request.getEmailRecipients().isBlank() ? null : request.getEmailRecipients());

        rule = ruleRepository.save(rule);
        log.info("Updated alert rule '{}' for project {}", rule.getName(), projectId);
        return toRuleResponse(rule);
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "AlertRule")
    @Transactional
    public void deleteRule(UUID projectId, UUID ruleId) {
        validateProjectAccess(projectId);
        AlertRule rule = ruleRepository.findByIdAndProjectId(ruleId, projectId)
                .orElseThrow(() -> new NotFoundException("Alert rule not found"));
        ruleRepository.delete(rule);
        log.info("Deleted alert rule '{}' from project {}", rule.getName(), projectId);
    }

    // ─── Alert Events ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<AlertEventResponse> listEvents(UUID projectId, int page, int size) {
        validateProjectAccess(projectId);
        return eventRepository.findByProjectIdOrderByCreatedAtDesc(projectId, PageRequest.of(page, Math.min(size, 100)))
                .map(this::toEventResponse);
    }

    @Transactional(readOnly = true)
    public long countUnresolved(UUID projectId) {
        validateProjectAccess(projectId);
        return eventRepository.countByProjectIdAndResolvedFalse(projectId);
    }

    @Transactional
    public void resolveEvent(UUID projectId, UUID eventId) {
        validateProjectAccess(projectId);
        int updated = eventRepository.resolveById(eventId, projectId, Instant.now());
        if (updated == 0) {
            throw new NotFoundException("Alert event not found");
        }
    }

    @Transactional
    public int resolveAll(UUID projectId) {
        validateProjectAccess(projectId);
        return eventRepository.resolveAllByProjectId(projectId, Instant.now());
    }

    // ─── Fire alert (called by evaluator) ───────────────────────────────

    @Transactional
    public AlertEvent fireAlert(AlertRule rule, double currentValue, String message) {
        AlertEvent event = AlertEvent.builder()
                .alertRuleId(rule.getId())
                .projectId(rule.getProjectId())
                .severity(rule.getSeverity())
                .title(rule.getName())
                .message(message)
                .currentValue(currentValue)
                .thresholdValue(rule.getThresholdValue())
                .build();

        event = eventRepository.save(event);
        log.warn("Alert fired: rule='{}', project={}, current={}, threshold={}",
                rule.getName(), rule.getProjectId(), currentValue, rule.getThresholdValue());

        // Send notification (Slack / Webhook / Email) — async, fire-and-forget
        notificationService.dispatch(rule, event);

        // Auto-create incident for CRITICAL severity alerts
        if (rule.getSeverity() == AlertSeverity.CRITICAL) {
            Incident incident = Incident.builder()
                    .projectId(rule.getProjectId())
                    .title("[Auto] " + rule.getName() + " — " + message)
                    .severity(AlertSeverity.CRITICAL)
                    .status(IncidentStatus.OPEN)
                    .build();
            incident = incidentRepository.save(incident);

            IncidentTimeline entry = IncidentTimeline.builder()
                    .incidentId(incident.getId())
                    .entryType(IncidentTimelineType.STATUS_CHANGE)
                    .title("Auto-created from alert rule: " + rule.getName())
                    .detail(String.format("Current value: %.2f, Threshold: %.2f", currentValue, rule.getThresholdValue()))
                    .build();
            timelineRepository.save(entry);

            log.info("Auto-created incident '{}' for CRITICAL alert rule '{}'", incident.getId(), rule.getName());
        }

        return event;
    }

    // ─── Mappers ────────────────────────────────────────────────────────

    private AlertRuleResponse toRuleResponse(AlertRule rule) {
        return AlertRuleResponse.builder()
                .id(rule.getId())
                .projectId(rule.getProjectId())
                .name(rule.getName())
                .description(rule.getDescription())
                .alertType(rule.getAlertType())
                .severity(rule.getSeverity())
                .channel(rule.getChannel())
                .thresholdValue(rule.getThresholdValue())
                .windowMinutes(rule.getWindowMinutes())
                .endpointId(rule.getEndpointId())
                .enabled(rule.getEnabled())
                .muted(rule.getMuted())
                .snoozedUntil(rule.getSnoozedUntil())
                .webhookUrl(rule.getWebhookUrl())
                .emailRecipients(rule.getEmailRecipients())
                .createdAt(rule.getCreatedAt())
                .updatedAt(rule.getUpdatedAt())
                .build();
    }

    private AlertEventResponse toEventResponse(AlertEvent event) {
        return AlertEventResponse.builder()
                .id(event.getId())
                .alertRuleId(event.getAlertRuleId())
                .projectId(event.getProjectId())
                .severity(event.getSeverity())
                .title(event.getTitle())
                .message(event.getMessage())
                .currentValue(event.getCurrentValue())
                .thresholdValue(event.getThresholdValue())
                .resolved(event.getResolved())
                .resolvedAt(event.getResolvedAt())
                .createdAt(event.getCreatedAt())
                .build();
    }

    private void validateProjectAccess(UUID projectId) {
        UUID organizationId = TenantContext.require();
        projectRepository.findById(projectId)
                .filter(p -> p.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }
}
