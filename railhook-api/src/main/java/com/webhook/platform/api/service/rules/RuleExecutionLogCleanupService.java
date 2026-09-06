package com.webhook.platform.api.service.rules;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.domain.repository.RuleExecutionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class RuleExecutionLogCleanupService {

    private final RuleExecutionLogRepository executionLogRepository;

    @Value("${rules.execution-log-retention-days:7}")
    private int retentionDays;

    @SystemTenant
    @Scheduled(cron = "0 30 2 * * *") // daily at 2:30 AM
    @SchedulerLock(name = "ruleExecutionLogCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = executionLogRepository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} rule execution log entries older than {} days", deleted, retentionDays);
        }
    }
}
