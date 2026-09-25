package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.SystemTenant;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drops whole partitions, O(1) where DELETE is O(rows). deliveries and incoming_events are not
 * partitioned because Postgres would force the partition key into their foreign keys.
 */
@Slf4j
@Service
public class PartitionMaintenanceService {

    // Identifiers are spliced into DDL text, so they must match this first.
    private static final java.util.regex.Pattern SAFE_IDENTIFIER =
            java.util.regex.Pattern.compile("^[a-z][a-z0-9_]*$");

    private static final DateTimeFormatter BOUND_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final int deliveryAttemptsLookaheadMonths;
    private final int tunnelRequestLogLookaheadWeeks;
    private final int deliveryAttemptsRetentionDays;
    private final int tunnelRequestLogRetentionDays;

    private final AtomicLong deliveryAttemptsDefaultPartitionRows = new AtomicLong(0);
    private final AtomicLong tunnelRequestLogDefaultPartitionRows = new AtomicLong(0);

    public PartitionMaintenanceService(
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry,
            @Value("${partition-maintenance.enabled:true}") boolean enabled,
            @Value("${partition-maintenance.delivery-attempts-lookahead-months:3}") int deliveryAttemptsLookaheadMonths,
            @Value("${partition-maintenance.tunnel-request-log-lookahead-weeks:3}") int tunnelRequestLogLookaheadWeeks,
            @Value("${data-retention.delivery-attempts-retention-days:90}") int deliveryAttemptsRetentionDays,
            @Value("${data-retention.tunnel-request-log-retention-days:7}") int tunnelRequestLogRetentionDays) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.deliveryAttemptsLookaheadMonths = deliveryAttemptsLookaheadMonths;
        this.tunnelRequestLogLookaheadWeeks = tunnelRequestLogLookaheadWeeks;
        this.deliveryAttemptsRetentionDays = deliveryAttemptsRetentionDays;
        this.tunnelRequestLogRetentionDays = tunnelRequestLogRetentionDays;

        Gauge.builder("partition_default_rows", deliveryAttemptsDefaultPartitionRows, AtomicLong::get)
                .tag("table", "delivery_attempts")
                .description("Rows landed in the DEFAULT partition — nonzero means partition maintenance fell behind")
                .register(meterRegistry);
        Gauge.builder("partition_default_rows", tunnelRequestLogDefaultPartitionRows, AtomicLong::get)
                .tag("table", "tunnel_request_log")
                .description("Rows landed in the DEFAULT partition — nonzero means partition maintenance fell behind")
                .register(meterRegistry);

        log.info("Partition maintenance configured: enabled={}, deliveryAttempts lookahead={}mo retention={}d, tunnelRequestLog lookahead={}wk retention={}d",
                enabled, deliveryAttemptsLookaheadMonths, deliveryAttemptsRetentionDays,
                tunnelRequestLogLookaheadWeeks, tunnelRequestLogRetentionDays);
    }

    @SystemTenant
    @Scheduled(cron = "${partition-maintenance.cron:0 45 1 * * *}")
    @SchedulerLock(name = "partitionMaintenance", lockAtMostFor = "9m", lockAtLeastFor = "1m")
    public void runMaintenance() {
        if (!enabled) {
            return;
        }
        try {
            ensureFutureMonthlyPartitions("delivery_attempts", deliveryAttemptsLookaheadMonths);
        } catch (Exception e) {
            log.error("Failed to create future delivery_attempts partitions", e);
        }
        try {
            ensureFutureWeeklyPartitions("tunnel_request_log", tunnelRequestLogLookaheadWeeks);
        } catch (Exception e) {
            log.error("Failed to create future tunnel_request_log partitions", e);
        }
        try {
            int dropped = dropExpiredPartitions("delivery_attempts", deliveryAttemptsRetentionDays);
            if (dropped > 0) {
                Counter.builder("partition_dropped_total").tag("table", "delivery_attempts")
                        .register(meterRegistry).increment(dropped);
                log.info("Dropped {} expired delivery_attempts partition(s) (retention {}d)", dropped, deliveryAttemptsRetentionDays);
            }
        } catch (Exception e) {
            log.error("Failed to drop expired delivery_attempts partitions", e);
        }
        try {
            int dropped = dropExpiredPartitions("tunnel_request_log", tunnelRequestLogRetentionDays);
            if (dropped > 0) {
                Counter.builder("partition_dropped_total").tag("table", "tunnel_request_log")
                        .register(meterRegistry).increment(dropped);
                log.info("Dropped {} expired tunnel_request_log partition(s) (retention {}d)", dropped, tunnelRequestLogRetentionDays);
            }
        } catch (Exception e) {
            log.error("Failed to drop expired tunnel_request_log partitions", e);
        }
        refreshDefaultPartitionGauges();
    }

    public void ensureFutureMonthlyPartitions(String table, int lookaheadMonths) {
        requireSafeIdentifier(table);
        LocalDate monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        for (int i = 0; i <= lookaheadMonths; i++) {
            LocalDate start = monthStart.plusMonths(i);
            LocalDate end = start.plusMonths(1);
            String partitionName = String.format("%s_y%04d_m%02d", table, start.getYear(), start.getMonthValue());
            createPartitionIfMissing(table, partitionName, start.atStartOfDay(), end.atStartOfDay());
        }
    }

    // ISO weeks starting Monday, matching Postgres date_trunc('week', ...).
    public void ensureFutureWeeklyPartitions(String table, int lookaheadWeeks) {
        requireSafeIdentifier(table);
        LocalDate weekStart = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (int i = 0; i <= lookaheadWeeks; i++) {
            LocalDate start = weekStart.plusWeeks(i);
            LocalDate end = start.plusWeeks(1);
            int[] isoYearWeek = isoYearWeek(start);
            String partitionName = String.format("%s_y%04d_w%02d", table, isoYearWeek[0], isoYearWeek[1]);
            createPartitionIfMissing(table, partitionName, start.atStartOfDay(), end.atStartOfDay());
        }
    }

    private int[] isoYearWeek(LocalDate date) {
        java.time.temporal.WeekFields iso = java.time.temporal.WeekFields.ISO;
        return new int[] {
                date.get(iso.weekBasedYear()),
                date.get(iso.weekOfWeekBasedYear())
        };
    }

    private void createPartitionIfMissing(String table, String partitionName, LocalDateTime start, LocalDateTime end) {
        requireSafeIdentifier(partitionName);
        String sql = String.format(
                "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')",
                partitionName, table, BOUND_FORMAT.format(start), BOUND_FORMAT.format(end));
        jdbcTemplate.execute(sql);
    }

    // The bound is compared in SQL because the tables mix TIMESTAMP and TIMESTAMPTZ.
    public int dropExpiredPartitions(String table, int retentionDays) {
        requireSafeIdentifier(table);
        List<String> expired = jdbcTemplate.queryForList(
                """
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = ?::regclass
                  AND pg_get_expr(c.relpartbound, c.oid) <> 'DEFAULT'
                  AND (regexp_match(pg_get_expr(c.relpartbound, c.oid), 'TO \\(''([^'']+)''\\)'))[1]::timestamptz
                      < now() - make_interval(days => ?)
                """,
                String.class, table, retentionDays);

        int dropped = 0;
        for (String partition : expired) {
            requireSafeIdentifier(partition);
            if (!partition.startsWith(table + "_")) {
                log.warn("Skipping partition {} of {} — name doesn't match the expected {}_ prefix", partition, table, table);
                continue;
            }
            log.info("Dropping expired partition {} of {} (fully older than {}d retention)", partition, table, retentionDays);
            jdbcTemplate.execute("DROP TABLE " + partition);
            dropped++;
        }
        return dropped;
    }

    private void refreshDefaultPartitionGauges() {
        try {
            Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM delivery_attempts_default", Long.class);
            deliveryAttemptsDefaultPartitionRows.set(rows == null ? 0 : rows);
            if (rows != null && rows > 0) {
                log.warn("delivery_attempts_default holds {} row(s) — partition maintenance has fallen behind", rows);
            }
        } catch (Exception e) {
            log.debug("Could not read delivery_attempts_default row count: {}", e.getMessage());
        }
        try {
            Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tunnel_request_log_default", Long.class);
            tunnelRequestLogDefaultPartitionRows.set(rows == null ? 0 : rows);
            if (rows != null && rows > 0) {
                log.warn("tunnel_request_log_default holds {} row(s) — partition maintenance has fallen behind", rows);
            }
        } catch (Exception e) {
            log.debug("Could not read tunnel_request_log_default row count: {}", e.getMessage());
        }
    }

    private void requireSafeIdentifier(String identifier) {
        if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Refusing to use as a SQL identifier: " + identifier);
        }
    }
}
