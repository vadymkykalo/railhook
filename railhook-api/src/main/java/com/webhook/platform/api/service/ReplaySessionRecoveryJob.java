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
 * Fails, not resumes, replays whose process died: an unasked resume is a fan-out nobody watches.
 * A live replay touches updated_at every batch, so staleness keeps it safe from this job.
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
