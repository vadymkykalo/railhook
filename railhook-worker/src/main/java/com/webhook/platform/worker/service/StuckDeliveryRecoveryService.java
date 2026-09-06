package com.webhook.platform.worker.service;

import com.webhook.platform.worker.domain.repository.DeliveryRepository;
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
public class StuckDeliveryRecoveryService {

    private static final String LOCK_KEY = "lock:stuck-delivery-recovery";

    private final DeliveryRepository deliveryRepository;
    private final ExclusiveSweep exclusiveSweep;

    @Value("${stuck-delivery.threshold-minutes:5}")
    private int thresholdMinutes;

    @Value("${stuck-delivery.stranded-pending-threshold-minutes:60}")
    private int strandedPendingThresholdMinutes;

    @Scheduled(fixedRateString = "${stuck-delivery.check-interval-ms:60000}")
    @Transactional
    public void recoverStuckDeliveries() {
        exclusiveSweep.run(LOCK_KEY, "Stuck delivery recovery", () -> {
            Instant threshold = Instant.now().minusSeconds(thresholdMinutes * 60L);
            int recovered = deliveryRepository.resetStuckDeliveries(threshold);
            if (recovered > 0) {
                log.warn("Recovered {} stuck deliveries (PROCESSING > {} minutes)", recovered, thresholdMinutes);
            }

            Instant strandedThreshold = Instant.now().minusSeconds(strandedPendingThresholdMinutes * 60L);
            int strandedRecovered = deliveryRepository.resetStrandedPendingDeliveries(strandedThreshold);
            if (strandedRecovered > 0) {
                log.warn("Recovered {} stranded PENDING deliveries (next_retry_at NULL > {} minutes)",
                        strandedRecovered, strandedPendingThresholdMinutes);
            }
        });
    }
}
