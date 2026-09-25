package com.webhook.platform.api;

import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StrandedSequenceRepositoryTest extends AbstractIntegrationTest {

    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private EndpointRepository endpointRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    private UUID orgId;
    private UUID projectId;
    private UUID endpointId;

    @BeforeEach
    void seed() {
        Plan plan = planRepository.findByName("self_hosted")
                .orElseGet(() -> planRepository.findAll().stream().findFirst().orElseThrow());
        Organization org = organizationRepository.save(
                Organization.builder().name("Seq " + UUID.randomUUID()).plan(plan).build());
        orgId = org.getId();
        projectId = projectRepository.save(
                Project.builder().organizationId(orgId).name("Payments").build()).getId();
        endpointId = endpointRepository.save(Endpoint.builder()
                .organizationId(orgId).projectId(projectId)
                .url("https://receiver.example.test/hook")
                .secretEncrypted("cipher").secretIv("iv").build()).getId();
    }

    private Delivery delivery(boolean ordered, Long sequence, DeliveryStatus status, Instant createdAt) {
        // A fresh Event per Delivery: idx_deliveries_unique_rule makes (event, endpoint) unique.
        UUID ownEventId = eventRepository.save(Event.builder()
                .organizationId(orgId).projectId(projectId)
                .eventType("payment.succeeded").payload("{}").build()).getId();
        Delivery saved = deliveryRepository.saveAndFlush(Delivery.builder()
                .organizationId(orgId).eventId(ownEventId).endpointId(endpointId)
                .orderingEnabled(ordered).sequenceNumber(sequence).status(status)
                .build());
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                entityManager.createNativeQuery("UPDATE deliveries SET created_at = ?1 WHERE id = ?2")
                        .setParameter(1, createdAt).setParameter(2, saved.getId()).executeUpdate());
        entityManager.clear();
        return saved;
    }

    private List<UUID> stranded() {
        return deliveryRepository
                .findOrderedDeliveriesMissingASequence(Instant.now().minusSeconds(120), 500)
                .stream().map(Delivery::getId).toList();
    }

    @Test
    @DisplayName("an ordered delivery that committed without a sequence is found")
    void findsTheStrandedRow() {
        Delivery lost = delivery(true, null, DeliveryStatus.PENDING, Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(stranded()).contains(lost.getId());
    }

    @Test
    @DisplayName("one whose backfill may still be in flight is left alone")
    void leavesTheInFlightWindowAlone() {
        // Without the age bound the sweep would race the ingest it repairs.
        Delivery justCommitted = delivery(true, null, DeliveryStatus.PENDING, Instant.now());

        assertThat(stranded()).doesNotContain(justCommitted.getId());
    }

    @Test
    @DisplayName("unordered deliveries are not stranded — they never wanted a number")
    void ignoresUnorderedDeliveries() {
        Delivery unordered = delivery(false, null, DeliveryStatus.PENDING, Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(stranded()).doesNotContain(unordered.getId());
    }

    @Test
    @DisplayName("one that already has its number is not touched again")
    void ignoresDeliveriesThatHaveOne() {
        Delivery fine = delivery(true, 42L, DeliveryStatus.PENDING, Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(stranded()).doesNotContain(fine.getId());
    }

    @Test
    @DisplayName("a resolved delivery is not repaired — its ordering question is already over")
    void ignoresResolvedDeliveries() {
        Delivery done = delivery(true, null, DeliveryStatus.SUCCESS, Instant.now().minus(1, ChronoUnit.HOURS));
        Delivery abandoned = delivery(true, null, DeliveryStatus.DLQ, Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(stranded()).doesNotContain(done.getId(), abandoned.getId());
    }

    // Both callers run the backfill with no transaction open.
    @Test
    @DisplayName("the backfill writes the number when called with no transaction open, as both callers do")
    void backfillRunsWithoutACallerTransaction() {
        Delivery ordered = delivery(true, null, DeliveryStatus.PENDING, Instant.now());

        int updated = deliveryRepository.updateSequenceNumber(ordered.getId(), 7L);

        assertThat(updated).isEqualTo(1);
        assertThat(deliveryRepository.findById(ordered.getId()).orElseThrow().getSequenceNumber()).isEqualTo(7L);
    }
}
