package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.enums.ReplaySessionStatus;
import com.webhook.platform.api.domain.repository.ReplaySessionRepository;
import com.webhook.platform.api.tenancy.SystemTenant;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Fails replay sessions nobody is running any more.
 *
 * <p>A session is owned by nothing but the thread replaying it. When that thread dies with its
 * process — a deploy, an OOM — the row stays PENDING or RUNNING for ever, and every such row holds
 * one of the project's concurrent-replay slots, so two of them lock the project out of replay.
 *
 * <p>Failed rather than resumed: the session keeps its cursor, but a replay resumed by a replica
 * the user never asked is a second fan-out nobody is watching. FAILED with a reason lets them
 * start it again knowingly.
 *
 * <p>There is no heartbeat column, and no need for one: a running replay writes its progress, and
 * so {@code updated_at}, after every batch. Staleness is what keeps a replay running on another,
 * live replica safe from this job, and runs every few minutes rather than at startup because a
 * restart's orphans are seconds old when the new process comes up. The cost is a session that
 * waited in a saturated executor's queue for longer than the threshold: it is failed too, and its
 * late launch then declines to start it.
 */
@Component
@Slf4j
public class ReplaySessionRecoveryJob {

    static final Duration STALE_AFTER = Duration.ofMinutes(15);

    private static final List<ReplaySessionStatus> ACTIVE = List.of(
            ReplaySessionStatus.PENDING, ReplaySessionStatus.ESTIMATING, ReplaySessionStatus.RUNNING);

    private final ReplaySessionRepository replaySessionRepository;

    public ReplaySessionRecoveryJob(ReplaySessionRepository replaySessionRepository) {
        this.replaySessionRepository = replaySessionRepository;
    }

    @SystemTenant("sweeps every organization's replay sessions")
    @Scheduled(fixedDelayString = "PT5M")
    @SchedulerLock(name = "replay-session-recovery", lockAtMostFor = "PT4M")
    @Transactional
    public void failOrphanedSessions() {
        Instant now = Instant.now();
        int failed = replaySessionRepository.failStaleSessions(
                ACTIVE, ReplaySessionStatus.FAILED,
                "Replay was interrupted: no progress for " + STALE_AFTER.toMinutes()
                        + " minutes, most likely because the server running it restarted. "
                        + "Start the replay again.",
                now.minus(STALE_AFTER), now);
        if (failed > 0) {
            log.warn("Failed {} orphaned replay sessions (no progress for {} minutes)",
                    failed, STALE_AFTER.toMinutes());
        }
    }
}
