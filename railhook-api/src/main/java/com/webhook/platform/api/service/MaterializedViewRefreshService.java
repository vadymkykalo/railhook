package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.SystemTenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MaterializedViewRefreshService {

    private final JdbcTemplate jdbcTemplate;

    @SystemTenant
    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "refresh-mv-delivery-stats", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void refreshDeliveryStats() {
        long start = System.currentTimeMillis();
        try {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_delivery_stats");
            long duration = System.currentTimeMillis() - start;
            log.info("Refreshed mv_delivery_stats in {}ms", duration);
        } catch (Exception e) {
            log.error("Failed to refresh mv_delivery_stats", e);
        }
    }

    @SystemTenant
    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "refresh-mv-incoming-stats", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void refreshIncomingStats() {
        long start = System.currentTimeMillis();
        try {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_incoming_stats");
            long duration = System.currentTimeMillis() - start;
            log.info("Refreshed mv_incoming_stats in {}ms", duration);
        } catch (Exception e) {
            log.error("Failed to refresh mv_incoming_stats", e);
        }
    }
}
