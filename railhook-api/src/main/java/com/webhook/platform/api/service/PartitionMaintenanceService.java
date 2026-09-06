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
 * Keeps the declaratively-partitioned high-volume tables ({@code delivery_attempts},
 * {@code tunnel_request_log} — see V052/V053 migrations) supplied with future
 * partitions and drops partitions once every row they can possibly contain is past
 * its retention window.
 * <p>
 * This is what replaces DELETE-based retention for these two tables:
 * {@code DROP TABLE <partition>} is O(1) — it unlinks the partition's files — instead
 * of the O(rows) scan-and-delete the old {@code DataRetentionService} jobs did.
 * <p>
 * {@code deliveries} and {@code incoming_events} are intentionally NOT partitioned by
 * this service (or the migrations it depends on) — both are the target of a foreign
 * key from another high-volume table ({@code delivery_attempts.delivery_id},
 * {@code incoming_forward_attempts.incoming_event_id}), and Postgres requires a
 * partitioned table's unique/PK indexes to include the partition key, which would
 * force the partition key onto the child tables too (composite FK) and touch both
 * JPA entity copies.
 */
@Slf4j
@Service
public class PartitionMaintenanceService {

    /** Table name is validated against this before ever being spliced into DDL text. */
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

    /** Creates monthly partitions for the current month through +lookaheadMonths, idempotently. */
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

    /** Creates weekly (ISO, Monday-start — matches Postgres date_trunc('week', ...)) partitions. */
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

    /**
     * Drops every partition of {@code table} (found via pg_catalog, never by naming
     * convention alone) whose entire range is older than {@code retentionDays}. The
     * DEFAULT partition and any partition whose bound this can't confidently parse are
     * always skipped — silence is the safe failure mode for a destructive operation.
     * The upper-bound comparison is done in SQL (casting the extracted bound text to
     * timestamptz) rather than parsed in Java, since the two partitioned tables here
     * mix TIMESTAMP and TIMESTAMPTZ columns and Postgres already knows how to compare
     * both correctly.
     */
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
                // Defense in depth: never drop something pg_catalog says is a child of
                // this table but whose name doesn't match our own naming convention.
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
