package com.webhook.platform.api.service;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.BulkReplayResponse;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sending a delivery again by hand must never overlap an Attempt already under way. Needs a real
 * database because the bulk path selects its rows through a Specification.
 */
class DeliveryReplayIntegrationTest extends AbstractIntegrationTest {

    @Autowired private DeliveryService deliveryService;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EndpointRepository endpointRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PlanRepository planRepository;

    private UUID orgId;
    private UUID projectId;
    private UUID endpointId;
    private AuthContext owner;

    @BeforeEach
    void seedFixtures() {
        Plan plan = planRepository.findByName("self_hosted")
                .orElseGet(() -> planRepository.findAll().stream().findFirst().orElseThrow());
        orgId = organizationRepository.save(
                Organization.builder().name("Acme " + UUID.randomUUID()).plan(plan).build()).getId();
        projectId = projectRepository.save(Project.builder()
                .organizationId(orgId).name("Payments").build()).getId();
        endpointId = endpointRepository.save(Endpoint.builder()
                .organizationId(orgId).projectId(projectId)
                .url("https://example.test/hook")
                .secretEncrypted("encrypted").secretIv("iv").build()).getId();
        owner = new AuthContext(UUID.randomUUID(), orgId, MembershipRole.OWNER, null, null);
    }

    @Test
    void bulkReplayWithoutAStatusFilterSendsOnlyFailedAndAbandonedDeliveries() {
        Delivery failed = persist(DeliveryStatus.FAILED, null, null);
        Delivery abandoned = persist(DeliveryStatus.DLQ, null, null);
        UUID claim = UUID.randomUUID();
        Delivery inFlight = persist(DeliveryStatus.PROCESSING, claim, null);
        Instant nextRetry = Instant.now().plusSeconds(600);
        Delivery waitingForRetry = persist(DeliveryStatus.PENDING, null, nextRetry);
        Delivery succeeded = persist(DeliveryStatus.SUCCESS, null, null);

        TenantContext.set(orgId);
        BulkReplayResponse response = deliveryService.bulkReplayDeliveries(
                null, null, null, projectId, null, owner);

        assertThat(response.getReplayed()).isEqualTo(2);
        assertThat(reload(failed).getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(reload(abandoned).getStatus()).isEqualTo(DeliveryStatus.PENDING);
        // An in-flight delivery reset to PENDING and announced again went out a second time.
        assertThat(reload(inFlight).getStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        assertThat(reload(inFlight).getClaimToken()).isEqualTo(claim);
        // One already on its ladder keeps its place instead of jumping the wait.
        assertThat(reload(waitingForRetry).getNextRetryAt()).isNotNull();
        assertThat(reload(succeeded).getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
    }

    @Test
    void replayingADeliveryInFlightIsRefusedAndLeavesItsClaimAlone() {
        UUID claim = UUID.randomUUID();
        Delivery inFlight = persist(DeliveryStatus.PROCESSING, claim, null);

        TenantContext.set(orgId);
        assertThatThrownBy(() -> deliveryService.replayDelivery(inFlight.getId(), owner))
                .isInstanceOf(ConflictException.class);

        Delivery reloaded = reload(inFlight);
        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        assertThat(reloaded.getClaimToken()).isEqualTo(claim);
    }

    private Delivery persist(DeliveryStatus status, UUID claimToken, Instant nextRetryAt) {
        // One event each: deliveries are unique per (event, endpoint, subscription).
        Event event = eventRepository.save(Event.builder()
                .organizationId(orgId).projectId(projectId)
                .eventType("payment.succeeded").payload("{\"amount\":1000}").build());
        return deliveryRepository.saveAndFlush(Delivery.builder()
                .organizationId(orgId)
                .eventId(event.getId())
                .endpointId(endpointId)
                .status(status)
                .attemptCount(2)
                .maxAttempts(7)
                .claimToken(claimToken)
                .nextRetryAt(nextRetryAt)
                .build());
    }

    private Delivery reload(Delivery delivery) {
        return deliveryRepository.findById(delivery.getId()).orElseThrow();
    }
}
