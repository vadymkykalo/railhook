package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.api.tenancy.SystemTenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.IntSupplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class DlqRetentionJob {

    private final DeliveryRepository deliveryRepository;
    private final IncomingForwardAttemptRepository forwardRepository;
    private final TransactionOperations transactions;
    @Value("${data-retention.dlq-retention-days:14}")
    private final int retentionDays;
    @Value("${data-retention.batch-size:1000}")
    private final int batchSize;

    @SystemTenant
    @Scheduled(cron = "${data-retention.cleanup-cron:0 0 2 * * *}")
    @SchedulerLock(name = "dlq-retention", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purgeExpiredDlq() {
        if (retentionDays < 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        long deliveries = deleteInBatches(() -> deliveryRepository.deleteExpiredDlq(cutoff, batchSize));
        long forwards = deleteInBatches(() -> forwardRepository.deleteExpiredDlq(cutoff, batchSize));
        if (deliveries + forwards > 0) {
            log.info("DLQ retention: deleted {} deliveries and {} forwards in the DLQ longer than {} days",
                    deliveries, forwards, retentionDays);
        }
    }

    private long deleteInBatches(IntSupplier batch) {
        long total = 0;
        int deleted;
        do {
            Integer result = transactions.execute(tx -> batch.getAsInt());
            deleted = result == null ? 0 : result;
            total += deleted;
        } while (deleted >= batchSize);
        return total;
    }
}
