package com.webhook.platform.api.domain.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class MaterializedViewRepository {

    private final JdbcTemplate jdbcTemplate;

    public MaterializedViewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Get delivery stats from materialized view (last 30 days)
     */
    public Map<String, Long> getDeliveryStatsByProject(UUID projectId) {
        String sql = "SELECT status, SUM(cnt) as total FROM mv_delivery_stats " +
                     "WHERE project_id = ? GROUP BY status";
        
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, projectId);
        Map<String, Long> result = new HashMap<>();
        
        for (Map<String, Object> row : rows) {
            String status = (String) row.get("status");
            Long count = ((Number) row.get("total")).longValue();
            result.put(status, count);
        }
        
        return result;
    }

    /**
     * Get delivery stats by endpoint from materialized view
     */
    public List<Map<String, Object>> getDeliveryStatsByEndpoints(List<UUID> endpointIds) {
        String placeholders = String.join(",", endpointIds.stream().map(id -> "?").toList());
        String sql = "SELECT project_id, status, SUM(cnt) as total FROM mv_delivery_stats " +
                     "WHERE project_id IN (" + placeholders + ") GROUP BY project_id, status";
        
        return jdbcTemplate.queryForList(sql, endpointIds.toArray());
    }

    /**
     * Get incoming events count from materialized view
     */
    public long getIncomingEventsCount(UUID projectId, Instant since) {
        LocalDate sinceDate = LocalDate.ofInstant(since, ZoneOffset.UTC);
        String sql = "SELECT COALESCE(SUM(event_count), 0) FROM mv_incoming_stats " +
                     "WHERE project_id = ? AND day >= ?";
        
        Long count = jdbcTemplate.queryForObject(sql, Long.class, projectId, sinceDate);
        return count != null ? count : 0L;
    }
}
