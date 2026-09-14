package com.webhook.platform.api.service;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.ReplaySession;
import com.webhook.platform.api.domain.entity.Subscription;
import com.webhook.platform.api.domain.enums.ReplaySessionStatus;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.ReplaySessionRepository;
import com.webhook.platform.api.domain.repository.SubscriptionRepository;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A replay session is a row the HTTP request commits, and a replay that runs afterwards on the
 * replay executor, one transaction per batch. It used to run inside the request's own transaction
 * on the request thread — {@code @Async} does not apply to a call a bean makes on itself — so
 * cancel and the concurrency cap could not see the session, and one failing batch rolled back
 * everything, the session included.
 *
 * <p>Both subscriptions are ordered and on one endpoint, so every event yields two deliveries on
 * that endpoint, and the sequence numbers the (mocked) generator hands out decide whether a batch
 * can commit.
 */
@TestPropertySource(properties = {"replay.batch-size=1", "replay.batch-delay-ms=0"})
class ReplaySessionLaunchIntegrationTest extends AbstractIntegrationTest {

    private static final Set<ReplaySessionStatus> FINISHED = Set.of(
            ReplaySessionStatus.COMPLETED, ReplaySessionStatus.FAILED, ReplaySessionStatus.CANCELLED);

    @Autowired private ReplayService replayService;
    @Autowired private ReplaySessionRepository replaySessionRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private EndpointRepository endpointRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PlanRepository planRepository;

    private UUID orgId;
    private UUID projectId;
    private Instant seededAt;

    @BeforeEach
    void seedTwoEventsForTwoOrderedSubscriptions() {
        Plan plan = planRepository.findByName("self_hosted")
                .orElseGet(() -> planRepository.findAll().stream().findFirst().orElseThrow());
        orgId = organizationRepository.save(
                Organization.builder().name("Acme " + UUID.randomUUID()).plan(plan).build()).getId();
        projectId = projectRepository.save(Project.builder()
                .organizationId(orgId).name("Payments").build()).getId();
        UUID endpointId = endpointRepository.save(Endpoint.builder()
                .organizationId(orgId).projectId(projectId)
                .url("https://example.test/hook")
                .secretEncrypted("encrypted").secretIv("iv").build()).getId();
        // An endpoint subscribes to an event type once, so the second subscription is a pattern.
        for (String eventType : List.of("payment.succeeded", "payment.*")) {
            subscriptionRepository.save(Subscription.builder()
                    .organizationId(orgId).projectId(projectId).endpointId(endpointId)
                    .eventType(eventType).enabled(true).orderingEnabled(true).build());
        }
        seededAt = Instant.now();
        for (int i = 0; i < 2; i++) {
            eventRepository.save(Event.builder()
                    .organizationId(orgId).projectId(projectId)
                    .eventType("payment.succeeded").payload("{\"amount\":1000}").build());
        }
    }

    @Test
    void createReturnsWithTheSessionCommittedBeforeTheReplayRuns() throws Exception {
        CountDownLatch replayMayProceed = new CountDownLatch(1);
        AtomicLong sequence = new AtomicLong();
        when(sequenceGeneratorService.nextSequence(any())).thenAnswer(invocation -> {
            replayMayProceed.await(30, TimeUnit.SECONDS);
            return sequence.incrementAndGet();
        });

        UUID sessionId;
        try {
            CompletableFuture<ReplaySessionResponse> request = CompletableFuture.supplyAsync(() ->
                    TenantContext.callAs(orgId, () -> replayService.create(projectId, replayRequest(), null)));
            ReplaySessionResponse created;
            try {
                created = request.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fail("create() did not return while the replay was held at its first batch: "
                        + "the replay ran on the request thread, inside the request's transaction");
                return;
            }
            sessionId = created.getId();
            assertThat(created.getStatus()).isEqualTo(ReplaySessionStatus.PENDING);
            // Visible from another transaction while the replay is still held: cancel and the
            // per-project cap can see it.
            assertThat(replaySessionRepository.findById(sessionId)).isPresent();
        } finally {
            replayMayProceed.countDown();
        }

        ReplaySession finished = awaitFinished(sessionId);
        assertThat(finished.getStatus()).isEqualTo(ReplaySessionStatus.COMPLETED);
        assertThat(finished.getDeliveriesCreated()).isEqualTo(4);
    }

    @Test
    void aBatchThatFailsDoesNotTakeTheSessionOrTheOtherBatchesWithIt() {
        // The first event's two deliveries get the same sequence number on the same endpoint, so
        // its batch violates the unique index; the second event's batch is fine.
        when(sequenceGeneratorService.nextSequence(any())).thenReturn(1L, 1L, 2L, 3L);

        ReplaySessionResponse created = TenantContext.callAs(orgId,
                () -> replayService.create(projectId, replayRequest(), null));

        ReplaySession finished = awaitFinished(created.getId());
        assertThat(finished.getStatus()).isEqualTo(ReplaySessionStatus.COMPLETED);
        assertThat(finished.getErrors()).isEqualTo(1);
        assertThat(finished.getProcessedEvents()).isEqualTo(1);
        assertThat(finished.getDeliveriesCreated()).isEqualTo(2);
    }

    /** No event type: a filtered replay loads only exact-type subscriptions, not the pattern. */
    private ReplayRequest replayRequest() {
        return ReplayRequest.builder()
                .fromDate(seededAt.minusSeconds(3600))
                .toDate(seededAt.plusSeconds(3600))
                .build();
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
