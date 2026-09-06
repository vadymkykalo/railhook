package com.webhook.platform.worker.service;

import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class StuckForwardRecoveryService {

    private static final String LOCK_KEY = "lock:stuck-forward-recovery";

    private final IncomingForwardAttemptRepository attemptRepository;
    private final ExclusiveSweep exclusiveSweep;

    @Value("${stuck-forward.threshold-minutes:5}")
    private int thresholdMinutes;

    @Value("${stuck-forward.stranded-pending-threshold-minutes:60}")
    private int strandedPendingThresholdMinutes;

    @Scheduled(fixedRateString = "${stuck-forward.check-interval-ms:60000}")
    @Transactional
    public void recoverStuckForwardAttempts() {
        exclusiveSweep.run(LOCK_KEY, "Stuck forward recovery", () -> {
            Instant threshold = Instant.now().minusSeconds(thresholdMinutes * 60L);
            int recovered = attemptRepository.resetStuckForwardAttempts(threshold);
            if (recovered > 0) {
                log.warn("Recovered {} stuck incoming forward attempts (PROCESSING > {} minutes)",
                        recovered, thresholdMinutes);
            }

            Instant strandedThreshold = Instant.now().minusSeconds(strandedPendingThresholdMinutes * 60L);
            int strandedRecovered = attemptRepository.resetStrandedPendingForwardAttempts(strandedThreshold);
            if (strandedRecovered > 0) {
                log.warn("Recovered {} stranded PENDING incoming forward attempts "
                        + "(next_retry_at NULL > {} minutes)", strandedRecovered, strandedPendingThresholdMinutes);
            }
        });
    }
}
