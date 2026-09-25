package com.webhook.platform.api.service;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.ReplaySession;
import com.webhook.platform.api.domain.enums.ReplaySessionStatus;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.ReplaySessionRepository;
import com.webhook.platform.api.dto.ReplayRequest;
import com.webhook.platform.api.dto.ReplaySessionResponse;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// A killed replay thread once left its session RUNNING forever, holding a replay slot.
@TestPropertySource(properties = {"replay.batch-size=1", "replay.batch-delay-ms=0"})
class ReplaySessionRecoveryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ReplaySessionRecoveryJob recoveryJob;
    @Autowired private ReplayService replayService;
    @Autowired private ReplaySessionRepository replaySessionRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("replayTaskExecutor") private Executor replayTaskExecutor;

    private UUID orgId;
    private UUID projectId;
    private Instant seededAt;

    @BeforeEach
    void seed() {
        Plan plan = planRepository.findByName("self_hosted")
                .orElseGet(() -> planRepository.findAll().stream().findFirst().orElseThrow());
        orgId = organizationRepository.save(
                Organization.builder().name("Acme " + UUID.randomUUID()).plan(plan).build()).getId();
        projectId = projectRepository.save(Project.builder()
                .organizationId(orgId).name("Payments").build()).getId();
        seededAt = Instant.now();
        eventRepository.save(Event.builder()
                .organizationId(orgId).projectId(projectId)
                .eventType("payment.succeeded").payload("{\"amount\":1000}").build());
    }

    @Test
    void aSessionNobodyHasTouchedForLongIsFailedAndALiveOneIsLeftAlone() {
        UUID orphanedRunning = session(ReplaySessionStatus.RUNNING, Instant.now().minus(1, ChronoUnit.HOURS));
        UUID orphanedPending = session(ReplaySessionStatus.PENDING, Instant.now().minus(1, ChronoUnit.HOURS));
        // Checkpointed a moment ago, as a replay running on another replica would have.
        UUID live = session(ReplaySessionStatus.RUNNING, Instant.now());
        UUID finished = session(ReplaySessionStatus.COMPLETED, Instant.now().minus(1, ChronoUnit.HOURS));

        recoveryJob.failOrphanedSessions();

        ReplaySession running = replaySessionRepository.findById(orphanedRunning).orElseThrow();
        assertThat(running.getStatus()).isEqualTo(ReplaySessionStatus.FAILED);
        assertThat(running.getErrorMessage()).containsIgnoringCase("interrupted");
        assertThat(running.getCompletedAt()).isNotNull();
        assertThat(replaySessionRepository.findById(orphanedPending).orElseThrow().getStatus())
                .isEqualTo(ReplaySessionStatus.FAILED);
        assertThat(replaySessionRepository.findById(live).orElseThrow().getStatus())
                .isEqualTo(ReplaySessionStatus.RUNNING);
        assertThat(replaySessionRepository.findById(finished).orElseThrow().getStatus())
                .isEqualTo(ReplaySessionStatus.COMPLETED);
    }

    @Test
    void aSessionThatIsNoLongerPendingIsNotStartedByALateLaunch() {
        // Failed as orphaned while it waited in the executor's queue: when its turn finally comes
        // it must not flip itself back to RUNNING and replay anyway.
        UUID failed = session(ReplaySessionStatus.FAILED, Instant.now());

        TenantContext.runAs(orgId, () -> replayService.run(failed));

        ReplaySession after = replaySessionRepository.findById(failed).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReplaySessionStatus.FAILED);
        assertThat(after.getStartedAt()).isNull();
        assertThat(after.getProcessedEvents()).isZero();
    }

    @Test
    void aReplayTheExecutorHasNoRoomForFailsTheSessionInsteadOfRunningOnTheRequestThread() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) replayTaskExecutor;
        CountDownLatch release = new CountDownLatch(1);
        List<Runnable> occupied = new ArrayList<>();
        int capacity = executor.getMaxPoolSize() + executor.getQueueCapacity();
        try {
            for (int i = 0; i < capacity; i++) {
                Runnable blocker = () -> {
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
                executor.execute(blocker);
                occupied.add(blocker);
            }

            ReplaySessionResponse created = CompletableFuture.supplyAsync(() ->
                    TenantContext.callAs(orgId, () -> replayService.create(projectId, replayRequest(), null)))
                    .get(20, TimeUnit.SECONDS);

            ReplaySession session = replaySessionRepository.findById(created.getId()).orElseThrow();
            assertThat(session.getStatus())
                    .as("neither replayed on the request thread nor left PENDING with nothing queued")
                    .isEqualTo(ReplaySessionStatus.FAILED);
            assertThat(session.getErrorMessage()).containsIgnoringCase("try again");
            assertThat(session.getProcessedEvents()).isZero();
        } finally {
            release.countDown();
        }
    }

    private UUID session(ReplaySessionStatus status, Instant lastTouched) {
        UUID id = replaySessionRepository.saveAndFlush(ReplaySession.builder()
                .organizationId(orgId).projectId(projectId).status(status)
                .fromDate(seededAt.minusSeconds(3600)).toDate(seededAt.plusSeconds(3600))
                .totalEvents(1)
                .build()).getId();
        jdbcTemplate.update("UPDATE replay_sessions SET updated_at = ? WHERE id = ?",
                Timestamp.from(lastTouched), id);
        return id;
    }

    private ReplayRequest replayRequest() {
        return ReplayRequest.builder()
                .fromDate(seededAt.minusSeconds(3600))
                .toDate(seededAt.plusSeconds(3600))
                .build();
    }
}
