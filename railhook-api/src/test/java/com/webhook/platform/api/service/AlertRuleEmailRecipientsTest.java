package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.AlertChannel;
import com.webhook.platform.api.domain.enums.AlertType;
import com.webhook.platform.api.domain.repository.AlertEventRepository;
import com.webhook.platform.api.domain.repository.AlertRuleRepository;
import com.webhook.platform.api.domain.repository.IncidentRepository;
import com.webhook.platform.api.domain.repository.IncidentTimelineRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.AlertRuleRequest;
import com.webhook.platform.api.tenancy.TenantContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An EMAIL rule sends a message, about once a minute while its condition holds, to every address
 * in it — with the rule's name, which the user typed, in the subject. Any address at all was
 * accepted, which made alert rules a way to send mail from this deployment's domain to whoever
 * the user chose.
 */
@DisplayName("Alert rule email recipients are addresses of the organization's verified members, at most ten")
class AlertRuleEmailRecipientsTest {

    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();

    @AfterAll
    static void closeValidators() {
        VALIDATORS.close();
    }

    @Nested
    @DisplayName("the request")
    class RequestValidation {

        private final Validator validator = VALIDATORS.getValidator();

        @Test
        void acceptsACommaSeparatedListOfAddresses() {
            assertThat(violations("ops@company.com, dev@company.com")).isEmpty();
        }

        @Test
        void acceptsNoRecipients_whichIsHowAnUpdateClearsThem() {
            assertThat(violations(null)).isEmpty();
            assertThat(violations("")).isEmpty();
        }

        @Test
        void refusesSomethingThatIsNotAnAddress() {
            assertThat(violations("ops@company.com, not-an-address")).isNotEmpty();
        }

        @Test
        void refusesAnEmptyEntryInTheList() {
            assertThat(violations("ops@company.com,,dev@company.com")).isNotEmpty();
        }

        @Test
        void refusesMoreThanTenAddresses() {
            String eleven = IntStream.rangeClosed(1, 11)
                    .mapToObj(i -> "member" + i + "@company.com")
                    .collect(Collectors.joining(","));
            String ten = IntStream.rangeClosed(1, 10)
                    .mapToObj(i -> "member" + i + "@company.com")
                    .collect(Collectors.joining(","));

            assertThat(violations(eleven)).isNotEmpty();
            assertThat(violations(ten)).isEmpty();
        }

        private Set<ConstraintViolation<AlertRuleRequest>> violations(String recipients) {
            AlertRuleRequest request = AlertRuleRequest.builder()
                    .name("rule").alertType(AlertType.FAILURE_RATE).thresholdValue(10.0)
                    .emailRecipients(recipients).build();
            return validator.validate(request);
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("the service")
    class MembershipRestriction {

        @Mock private AlertRuleRepository ruleRepository;
        @Mock private AlertEventRepository eventRepository;
        @Mock private ProjectRepository projectRepository;
        @Mock private IncidentRepository incidentRepository;
        @Mock private IncidentTimelineRepository timelineRepository;
        @Mock private AlertNotificationService notificationService;
        @Mock private MembershipRepository membershipRepository;

        private AlertService service;

        private final UUID organizationId = UUID.randomUUID();
        private final UUID projectId = UUID.randomUUID();
        private final UUID ruleId = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            TenantContext.set(organizationId);
            service = new AlertService(ruleRepository, eventRepository, projectRepository,
                    incidentRepository, timelineRepository, notificationService, membershipRepository,
                    false, Collections.emptyList());

            when(projectRepository.findById(projectId))
                    .thenReturn(Optional.of(Project.builder().id(projectId).organizationId(organizationId).name("p").build()));
            when(ruleRepository.save(any(AlertRule.class))).thenAnswer(inv -> inv.getArgument(0));
            when(ruleRepository.findByIdAndProjectId(ruleId, projectId))
                    .thenReturn(Optional.of(AlertRule.builder()
                            .id(ruleId).projectId(projectId).name("existing")
                            .alertType(AlertType.FAILURE_RATE).build()));
            // The organization's verified members: whichever of the asked-for addresses are theirs.
            Set<String> members = Set.of("ops@company.com", "dev@company.com");
            when(membershipRepository.findVerifiedMemberEmailsIn(anyCollection())).thenAnswer(inv -> {
                Collection<String> asked = inv.getArgument(0);
                return asked.stream().filter(members::contains).toList();
            });
        }

        @AfterEach
        void clearTenant() {
            TenantContext.clear();
        }

        @Test
        void refusesAnAddressThatIsNotAVerifiedMemberOnCreate() {
            assertThatThrownBy(() -> service.createRule(projectId, request("ops@company.com, victim@elsewhere.com")))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

            verify(ruleRepository, never()).save(any());
        }

        @Test
        void refusesAnAddressThatIsNotAVerifiedMemberOnUpdate() {
            assertThatThrownBy(() -> service.updateRule(projectId, ruleId, request("victim@elsewhere.com")))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

            verify(ruleRepository, never()).save(any());
        }

        @Test
        void acceptsMembersAddresses_matchedWithoutRegardToCase_andStoresThemNormalized() {
            service.createRule(projectId, request(" Ops@Company.com ,dev@company.com"));

            ArgumentCaptor<AlertRule> saved = ArgumentCaptor.forClass(AlertRule.class);
            verify(ruleRepository).save(saved.capture());
            assertThat(saved.getValue().getEmailRecipients()).isEqualTo("ops@company.com,dev@company.com");
        }

        @Test
        void clearingTheRecipientsOnUpdateAsksNobody() {
            service.updateRule(projectId, ruleId, request(""));

            verify(membershipRepository, never()).findVerifiedMemberEmailsIn(anyCollection());
        }

        private AlertRuleRequest request(String recipients) {
            return AlertRuleRequest.builder()
                    .name("rule").alertType(AlertType.FAILURE_RATE).thresholdValue(10.0)
                    .channel(AlertChannel.EMAIL).emailRecipients(recipients).build();
        }
    }
}
