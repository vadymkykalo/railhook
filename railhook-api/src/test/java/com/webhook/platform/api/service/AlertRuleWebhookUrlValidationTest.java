package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.AlertType;
import com.webhook.platform.api.domain.repository.AlertEventRepository;
import com.webhook.platform.api.domain.repository.AlertRuleRepository;
import com.webhook.platform.api.domain.repository.IncidentRepository;
import com.webhook.platform.api.domain.repository.IncidentTimelineRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.AlertRuleRequest;
import com.webhook.platform.common.security.UrlValidator;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An alert rule's notification webhook is a URL the server fetches on the user's behalf, so it
 * is an SSRF sink like any other — and it was the one outbound URL in the product nobody
 * validated.
 *
 * <p>It was unreachable while nothing evaluated rules, which is why it went unnoticed. The
 * evaluator that now fires alerts is exactly what makes it reachable, so the validation lands
 * in the same change rather than after it: otherwise the commit that fixed alerting would have
 * been the commit that opened an authenticated SSRF onto the cloud metadata endpoint.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AlertService — a rule's notification URL is validated like any other outbound URL")
class AlertRuleWebhookUrlValidationTest {

    @Mock private AlertRuleRepository ruleRepository;
    @Mock private AlertEventRepository eventRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private IncidentTimelineRepository timelineRepository;
    @Mock private AlertNotificationService notificationService;

    private AlertService service;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID ruleId = UUID.randomUUID();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @BeforeEach
    void setUp() {
        // validateProjectAccess resolves the organization as a value, so these tests have to
        // enter a scope the way a request would.
        TenantContext.set(organizationId);
        service = new AlertService(ruleRepository, eventRepository, projectRepository,
                incidentRepository, timelineRepository, notificationService,
                false, Collections.emptyList());

        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(Project.builder().id(projectId).organizationId(organizationId).name("p").build()));
        when(ruleRepository.save(any(AlertRule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepository.findByIdAndProjectId(ruleId, projectId))
                .thenReturn(Optional.of(AlertRule.builder()
                        .id(ruleId).projectId(projectId).name("existing")
                        .alertType(AlertType.FAILURE_RATE).build()));
    }

    @Test
    @DisplayName("the cloud metadata endpoint is refused on create")
    void metadataEndpointRefusedOnCreate() {
        assertThatThrownBy(() -> service.createRule(projectId, request("http://169.254.169.254/latest/meta-data/")))
                .isInstanceOf(UrlValidator.InvalidUrlException.class);

        verify(ruleRepository, never()).save(any());
    }

    @Test
    @DisplayName("a private address is refused on update too — the hole is not only on create")
    void privateAddressRefusedOnUpdate() {
        assertThatThrownBy(() -> service.updateRule(projectId, ruleId, request("http://127.0.0.1:8080/admin")))
                .isInstanceOf(UrlValidator.InvalidUrlException.class);

        verify(ruleRepository, never()).save(any());
    }

    @Test
    @DisplayName("a rule with no notification URL is unaffected")
    void nullUrlIsFine() {
        assertThatCode(() -> service.createRule(projectId, request(null))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("clearing the URL with a blank string is not a validation failure")
    void blankUrlClearsRatherThanFails() {
        /* updateRule treats blank as "remove the URL". Running that through the validator
           would make the only way to unset a bad URL impossible. */
        assertThatCode(() -> service.updateRule(projectId, ruleId, request("  ")))
                .doesNotThrowAnyException();
    }

    private AlertRuleRequest request(String webhookUrl) {
        AlertRuleRequest r = new AlertRuleRequest();
        r.setName("rule");
        r.setAlertType(AlertType.FAILURE_RATE);
        r.setThresholdValue(50.0);
        r.setWebhookUrl(webhookUrl);
        return r;
    }
}
