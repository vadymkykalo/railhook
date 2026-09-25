package com.webhook.platform.api.service;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.ReplaySession;
import com.webhook.platform.api.domain.entity.Rule;
import com.webhook.platform.api.domain.entity.RuleAction;
import com.webhook.platform.api.domain.entity.Subscription;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.enums.DeliveryOrigin;
import com.webhook.platform.api.domain.enums.ReplaySessionStatus;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.ReplaySessionRepository;
import com.webhook.platform.api.domain.repository.RuleActionRepository;
import com.webhook.platform.api.domain.repository.RuleRepository;
import com.webhook.platform.api.domain.repository.SubscriptionRepository;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.dto.ReplayRequest;
import com.webhook.platform.api.dto.ReplaySessionResponse;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

// Replay once carried its own matching and skipped patterns and rules.
@TestPropertySource(properties = {
        "replay.batch-delay-ms=0",
        // Small enough that three subscriptions exceed it and two do not.
        "entitlement.defaults.max-fanout-per-event=2"
})
class ReplayIntakeIntegrationTest extends AbstractIntegrationTest {

    private static final Set<ReplaySessionStatus> FINISHED = Set.of(
            ReplaySessionStatus.COMPLETED, ReplaySessionStatus.FAILED, ReplaySessionStatus.CANCELLED);
    private static final String TYPE = "payment.succeeded";

    @Autowired private ReplayService replayService;
    @Autowired private ReplaySessionRepository replaySessionRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private EndpointRepository endpointRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private RuleRepository ruleRepository;
    @Autowired private RuleActionRepository ruleActionRepository;
    @Autowired private TransformationRepository transformationRepository;

    private UUID orgId;
    private UUID projectId;
    private Instant seededAt;

    @BeforeEach
    void seedProjectWithOneEvent() {
        Plan plan = planRepository.findByName("self_hosted")
                .orElseGet(() -> planRepository.findAll().stream().findFirst().orElseThrow());
        orgId = organizationRepository.save(
                Organization.builder().name("Acme " + UUID.randomUUID()).plan(plan).build()).getId();
        projectId = projectRepository.save(Project.builder()
                .organizationId(orgId).name("Payments").build()).getId();
        seededAt = Instant.now();
        eventRepository.save(Event.builder()
                .organizationId(orgId).projectId(projectId)
                .eventType(TYPE).payload("{\"amount\":1000}").build());
    }

    @Test
    void aPatternSubscriptionIsReachedWhenTheReplayIsFilteredByEventType() {
        UUID endpoint = endpoint();
        subscribe(endpoint, "payment.*");

        List<Delivery> deliveries = replay(TYPE);

        assertThat(deliveries).extracting(Delivery::getEndpointId).containsExactly(endpoint);
    }

    @Test
    void aRuleThatDropsTheEventDropsItsReplayToo() {
        subscribe(endpoint(), TYPE);
        rule(RuleAction.builder().type(RuleAction.ActionType.DROP));

        assertThat(replay(null)).isEmpty();
    }

    @Test
    void aRuleThatRoutesTheEventRoutesItsReplayToo() {
        UUID subscribed = endpoint();
        UUID routed = endpoint();
        subscribe(subscribed, TYPE);
        rule(RuleAction.builder().type(RuleAction.ActionType.ROUTE).endpointId(routed));

        List<Delivery> deliveries = replay(null);

        assertThat(deliveries).extracting(Delivery::getEndpointId).containsExactlyInAnyOrder(subscribed, routed);
        Delivery viaRule = deliveries.stream().filter(d -> d.getEndpointId().equals(routed)).findFirst().orElseThrow();
        assertThat(viaRule.getDeliveryOrigin()).isEqualTo(DeliveryOrigin.RULE);
        assertThat(viaRule.getSubscriptionId()).isNull();
    }

    @Test
    void aRuleTransformationOverridesTheSubscriptionsOnReplay() {
        UUID endpoint = endpoint();
        UUID subscriptionTransform = transformation("subscription");
        UUID ruleTransform = transformation("rule");
        subscriptionRepository.save(Subscription.builder()
                .organizationId(orgId).projectId(projectId).endpointId(endpoint)
                .eventType(TYPE).enabled(true).transformationId(subscriptionTransform).build());
        rule(RuleAction.builder().type(RuleAction.ActionType.TRANSFORM).transformationId(ruleTransform));

        List<Delivery> deliveries = replay(null);

        assertThat(deliveries).singleElement()
                .extracting(Delivery::getTransformationId).isEqualTo(ruleTransform);
    }

    @Test
    void anEventOverTheFanoutLimitIsNotReplayedToAnyEndpoint() {
        subscribe(endpoint(), TYPE);
        subscribe(endpoint(), TYPE);
        subscribe(endpoint(), TYPE);

        ReplaySession finished = runReplay(null);

        assertThat(deliveriesOf(finished)).isEmpty();
        assertThat(finished.getStatus()).isEqualTo(ReplaySessionStatus.COMPLETED);
        assertThat(finished.getErrors()).isEqualTo(1);
    }

    private UUID endpoint() {
        return endpointRepository.save(Endpoint.builder()
                .organizationId(orgId).projectId(projectId)
                .url("https://example.test/hook/" + UUID.randomUUID())
                .secretEncrypted("encrypted").secretIv("iv").build()).getId();
    }

    private void subscribe(UUID endpointId, String eventType) {
        subscriptionRepository.save(Subscription.builder()
                .organizationId(orgId).projectId(projectId).endpointId(endpointId)
                .eventType(eventType).enabled(true).build());
    }

    private UUID transformation(String name) {
        return transformationRepository.save(Transformation.builder()
                .organizationId(orgId).projectId(projectId)
                .name(name + " " + UUID.randomUUID()).template("{\"amount\":\"$.amount\"}").build()).getId();
    }

    private void rule(RuleAction.RuleActionBuilder action) {
        Rule rule = ruleRepository.saveAndFlush(Rule.builder()
                .organizationId(orgId).projectId(projectId)
                .name("rule " + UUID.randomUUID()).eventTypePattern("payment.*").build());
        ruleActionRepository.saveAndFlush(action.organizationId(orgId).ruleId(rule.getId()).build());
    }

    private List<Delivery> replay(String eventType) {
        return deliveriesOf(runReplay(eventType));
    }

    private List<Delivery> deliveriesOf(ReplaySession session) {
        return deliveryRepository.findAll().stream()
                .filter(d -> session.getId().equals(d.getReplaySessionId()))
                .toList();
    }

    private ReplaySession runReplay(String eventType) {
        ReplayRequest request = ReplayRequest.builder()
                .fromDate(seededAt.minusSeconds(3600))
                .toDate(seededAt.plusSeconds(3600))
                .eventType(eventType)
                .build();
        ReplaySessionResponse created = TenantContext.callAs(orgId,
                () -> replayService.create(projectId, request, null));
        return awaitFinished(created.getId());
    }

    private ReplaySession awaitFinished(UUID sessionId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            ReplaySession session = replaySessionRepository.findById(sessionId)
                    .orElseThrow(() -> new AssertionError("replay session " + sessionId + " was rolled back"));
            if (FINISHED.contains(session.getStatus())) {
                return session;
            }
            if (System.nanoTime() > deadline) {
                fail("replay session " + sessionId + " still " + session.getStatus() + " after 30s");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
