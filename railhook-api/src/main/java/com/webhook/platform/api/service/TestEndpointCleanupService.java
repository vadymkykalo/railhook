package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.domain.repository.CapturedRequestRepository;
import com.webhook.platform.api.domain.repository.TestEndpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class TestEndpointCleanupService {

    private final TestEndpointRepository testEndpointRepository;
    private final CapturedRequestRepository capturedRequestRepository;

    @SystemTenant
    @Scheduled(fixedRateString = "${test-endpoint.cleanup-interval-ms:3600000}")
    @SchedulerLock(name = "testEndpointCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanupExpiredEndpoints() {
        Instant now = Instant.now();

        int deletedRequests = capturedRequestRepository.deleteExpiredRequests(now);
        int deletedEndpoints = testEndpointRepository.deleteExpired(now);

        if (deletedEndpoints > 0 || deletedRequests > 0) {
            log.info("Cleaned up {} expired test endpoints and {} captured requests",
                    deletedEndpoints, deletedRequests);
        }
    }
}
