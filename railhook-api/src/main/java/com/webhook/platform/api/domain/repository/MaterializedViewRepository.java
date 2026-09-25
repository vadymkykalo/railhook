package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plain JDBC, so {@code @TenantId} does not apply and the native-query ratchet cannot see it.
 * The organization predicate is written by hand from {@link TenantContext#require()} rather than
 * taken as an argument, so a caller cannot pass the wrong one.
 */
@Repository
public class MaterializedViewRepository {

    private final JdbcTemplate jdbcTemplate;

    public MaterializedViewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Long> getDeliveryStatsByProject(UUID projectId) {
        String sql = "SELECT status, SUM(cnt) as total FROM mv_delivery_stats "
                + "WHERE organization_id = ? AND project_id = ? GROUP BY status";

        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(sql, TenantContext.require(), projectId);
        Map<String, Long> result = new HashMap<>();

        for (Map<String, Object> row : rows) {
            String status = (String) row.get("status");
            Long count = ((Number) row.get("total")).longValue();
            result.put(status, count);
        }

        return result;
    }

    public long getIncomingEventsCount(UUID projectId, Instant since) {
        LocalDate sinceDate = LocalDate.ofInstant(since, ZoneOffset.UTC);
        String sql = "SELECT COALESCE(SUM(event_count), 0) FROM mv_incoming_stats "
                + "WHERE organization_id = ? AND project_id = ? AND day >= ?";

        Long count = jdbcTemplate.queryForObject(
                sql, Long.class, TenantContext.require(), projectId, sinceDate);
        return count != null ? count : 0L;
    }
}
