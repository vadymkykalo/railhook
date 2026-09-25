package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.ReplaySession;
import com.webhook.platform.api.domain.entity.Rule;
import com.webhook.platform.api.domain.entity.Subscription;
import com.webhook.platform.api.domain.entity.RuleAction;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

// Without @JdbcTypeCode(SqlTypes.JSON) the jsonb columns bind as varchar and inserts fail.
class RuleAndReplayPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private RuleRepository ruleRepository;
    @Autowired private RuleActionRepository ruleActionRepository;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EndpointRepository endpointRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private ReplaySessionRepository replaySessionRepository;

    private UUID orgId;
    private UUID projectId;
    private UUID eventId;
    private UUID endpointId;
    private UUID subscriptionId;

    @BeforeEach
    void seedFixtures() {
        Plan plan = planRepository.findByName("self_hosted")
                .orElseGet(() -> planRepository.findAll().stream().findFirst().orElseThrow());
        Organization org = organizationRepository.save(
                Organization.builder().name("Acme " + UUID.randomUUID()).plan(plan).build());
        orgId = org.getId();

        Project project = projectRepository.save(Project.builder()
                .organizationId(orgId).name("Payments").build());
        projectId = project.getId();

        Endpoint endpoint = endpointRepository.save(Endpoint.builder()
                .organizationId(orgId).projectId(projectId)
                .url("https://example.test/hook")
                .secretEncrypted("encrypted").secretIv("iv").build());
        endpointId = endpoint.getId();

        Event event = eventRepository.save(Event.builder()
                .organizationId(orgId).projectId(projectId)
                .eventType("payment.succeeded")
                .payload("{\"amount\":1000}").build());
        eventId = event.getId();

        Subscription subscription = subscriptionRepository.save(Subscription.builder()
                .organizationId(orgId).projectId(projectId)
                .endpointId(endpointId).eventType("payment.succeeded").build());
        subscriptionId = subscription.getId();
    }

    @Test
    void persistsRuleConditionsAndActionConfigAsJsonb() {
        Rule saved = ruleRepository.saveAndFlush(Rule.builder()
                .organizationId(orgId).projectId(projectId)
                .name("Route high-value payments")
                .eventTypePattern("payment.*")
                .conditions("{\"type\":\"group\",\"op\":\"AND\",\"children\":[]}")
                .build());

        RuleAction savedAction = ruleActionRepository.saveAndFlush(RuleAction.builder()
                .organizationId(orgId)
                .ruleId(saved.getId())
                .type(RuleAction.ActionType.TAG)
                .config("{\"tag\":\"high-value\"}")
                .sortOrder(0)
                .build());

        assertThat(ruleRepository.findById(saved.getId()).orElseThrow().getConditions())
                .contains("\"op\"");
        assertThat(ruleActionRepository.findById(savedAction.getId()).orElseThrow().getConfig())
                .contains("high-value");
    }

    @Test
    void replayDeliveryCoexistsWithTheOriginalButNotWithItsOwnDuplicate() {
        deliveryRepository.saveAndFlush(delivery(null));

        UUID replaySessionId = newReplaySession();
        assertThatCode(() -> deliveryRepository.saveAndFlush(delivery(replaySessionId)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> deliveryRepository.saveAndFlush(delivery(replaySessionId)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> deliveryRepository.saveAndFlush(delivery(newReplaySession())))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsASecondOrdinaryDeliveryForTheSameSubscription() {
        deliveryRepository.saveAndFlush(delivery(null));

        assertThatThrownBy(() -> deliveryRepository.saveAndFlush(delivery(null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID newReplaySession() {
        return replaySessionRepository.saveAndFlush(ReplaySession.builder()
                .organizationId(orgId).projectId(projectId)
                .fromDate(Instant.now().minusSeconds(3600)).toDate(Instant.now())
                .build()).getId();
    }

    private Delivery delivery(UUID replaySessionId) {
        return Delivery.builder()
                .organizationId(orgId)
                .eventId(eventId)
                .endpointId(endpointId)
                .subscriptionId(subscriptionId)
                .replaySessionId(replaySessionId)
                .status(DeliveryStatus.PENDING)
                .build();
    }
}
