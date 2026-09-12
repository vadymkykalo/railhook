package com.webhook.platform.api;

import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.WorkflowTriggerOutbox;
import com.webhook.platform.api.domain.enums.WorkflowTriggerOutboxStatus;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.WorkflowTriggerOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import jakarta.persistence.EntityManager;
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

/**
 * What "stalled" has to mean for a row that is allowed to wait.
 *
 * <p>The sweep measured {@code created_at} — the moment the event was ingested — and called
 * anything older than the threshold abandoned. But a row is deliberately held in PENDING whenever
 * its project is at its concurrency ceiling: {@code deferToNextPoll} puts it back on every poll,
 * which is the mechanism that stops one project taking the whole workflow pool. So a busy
 * project's rows are routinely older than the threshold *before they are claimed for the first
 * time*, and the sweep then flipped them back to PENDING while a live executor was running them.
 * The next poll re-claimed them and the workflow ran a second time, concurrently with itself,
 * burning an attempt each round until the row was marked FAILED having executed repeatedly.
 *
 * <p>The method's own javadoc says the threshold has to exceed the longest legitimate run.
 * {@code created_at} does not measure the run.
 */
class WorkflowTriggerOutboxReclaimRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private WorkflowTriggerOutboxRepository repository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PlanRepository planRepository;

    private UUID projectId;
    private UUID eventId;

    @BeforeEach
    void seedTheEventTheRowHangsOff() {
        Plan plan = planRepository.findByName("self_hosted")
                .orElseGet(() -> planRepository.findAll().stream().findFirst().orElseThrow());
        Organization org = organizationRepository.save(
                Organization.builder().name("Acme " + UUID.randomUUID()).plan(plan).build());
        Project project = projectRepository.save(Project.builder()
                .organizationId(org.getId()).name("Payments").build());
        projectId = project.getId();
        eventId = eventRepository.save(Event.builder()
                .organizationId(org.getId()).projectId(projectId)
                .eventType("order.completed").payload("{}").build()).getId();
    }

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    private int inTransaction(java.util.function.IntSupplier update) {
        int rows = new TransactionTemplate(transactionManager).execute(tx -> update.getAsInt());
        entityManager.clear();
        return rows;
    }

    private WorkflowTriggerOutbox pending(Instant createdAt) {
        WorkflowTriggerOutbox row = repository.saveAndFlush(WorkflowTriggerOutbox.builder()
                .projectId(projectId)
                .eventId(eventId)
                .eventType("order.completed")
                .eventPayload("{}")
                .status(WorkflowTriggerOutboxStatus.PENDING)
                .build());
        // created_at is insertable-only, so it is backdated through SQL rather than the entity.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                entityManager.createNativeQuery(
                                "UPDATE workflow_trigger_outbox SET created_at = ?1 WHERE id = ?2")
                        .setParameter(1, createdAt)
                        .setParameter(2, row.getId())
                        .executeUpdate());
        entityManager.clear();
        return row;
    }

    @Test
    @DisplayName("a long-queued row claimed a moment ago is running, not stalled")
    void doesNotReclaimARowItJustHandedOut() {
        WorkflowTriggerOutbox queuedSinceThisMorning = pending(Instant.now().minus(4, ChronoUnit.HOURS));

        List<WorkflowTriggerOutbox> claimed = inTransactionList();
        assertThat(claimed).extracting(WorkflowTriggerOutbox::getId)
                .contains(queuedSinceThisMorning.getId());

        int reclaimed = inTransaction(() ->
                repository.reclaimStalledRows(Instant.now().minus(15, ChronoUnit.MINUTES)));

        assertThat(reclaimed).as("it was claimed seconds ago — the executor still has it").isZero();
        assertThat(repository.findById(queuedSinceThisMorning.getId()).orElseThrow().getStatus())
                .isEqualTo(WorkflowTriggerOutboxStatus.PROCESSING);
    }

    @Test
    @DisplayName("a row claimed long ago and never answered for is stalled, and comes back")
    void reclaimsARowWhoseExecutorDied() {
        WorkflowTriggerOutbox row = pending(Instant.now().minus(4, ChronoUnit.HOURS));
        inTransactionList();

        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                entityManager.createNativeQuery(
                                "UPDATE workflow_trigger_outbox SET claimed_at = ?1 WHERE id = ?2")
                        .setParameter(1, Instant.now().minus(30, ChronoUnit.MINUTES))
                        .setParameter(2, row.getId())
                        .executeUpdate());
        entityManager.clear();

        int reclaimed = inTransaction(() ->
                repository.reclaimStalledRows(Instant.now().minus(15, ChronoUnit.MINUTES)));

        assertThat(reclaimed).isEqualTo(1);
        assertThat(repository.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(WorkflowTriggerOutboxStatus.PENDING);
    }

    @Test
    @DisplayName("claiming stamps the moment it happened")
    void claimStampsClaimedAt() {
        WorkflowTriggerOutbox row = pending(Instant.now().minus(4, ChronoUnit.HOURS));

        inTransactionList();

        WorkflowTriggerOutbox after = repository.findById(row.getId()).orElseThrow();
        assertThat(after.getClaimedAt())
                .isNotNull()
                .isAfter(Instant.now().minus(1, ChronoUnit.MINUTES));
    }

    private List<WorkflowTriggerOutbox> inTransactionList() {
        List<WorkflowTriggerOutbox> claimed =
                new TransactionTemplate(transactionManager).execute(tx -> repository.claimBatch(50, 10));
        entityManager.clear();
        return claimed;
    }
}
