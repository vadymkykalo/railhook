package com.webhook.platform.worker.attempt;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Project deletion and Organization suspension do not touch the Endpoints, Sources or Destinations
 * underneath, so without this check queued work kept being sent for the rest of its Ladder.
 * Native SQL because the worker maps no entity for those tables. Cached briefly per id.
 */
@Component
public class ProjectStatusLookup {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final long MAX_CACHED = 10_000;

    /** Long enough that a suspended backlog is not re-claimed every few seconds. */
    public static final Duration SUSPENSION_RECHECK = Duration.ofMinutes(5);

    public enum ProjectStatus {
        ACTIVE,
        DELETED,
        ORGANIZATION_SUSPENDED
    }

    private static final String STATUS_COLUMNS =
            "SELECT p.deleted_at IS NOT NULL AS deleted, o.suspended_at IS NOT NULL AS suspended ";

    private static final String BY_PROJECT = STATUS_COLUMNS
            + "FROM projects p JOIN organizations o ON o.id = p.organization_id WHERE p.id = ?";

    private static final String BY_SOURCE = STATUS_COLUMNS
            + "FROM incoming_sources s JOIN projects p ON p.id = s.project_id "
            + "JOIN organizations o ON o.id = p.organization_id WHERE s.id = ?";

    private final JdbcTemplate jdbc;
    private final Cache<UUID, ProjectStatus> byProject;
    private final Cache<UUID, ProjectStatus> bySource;

    @Autowired
    public ProjectStatusLookup(JdbcTemplate jdbc) {
        this(jdbc, CACHE_TTL);
    }

    ProjectStatusLookup(JdbcTemplate jdbc, Duration ttl) {
        this.jdbc = jdbc;
        this.byProject = Caffeine.newBuilder().maximumSize(MAX_CACHED).expireAfterWrite(ttl).build();
        this.bySource = Caffeine.newBuilder().maximumSize(MAX_CACHED).expireAfterWrite(ttl).build();
    }

    public ProjectStatus forProject(UUID projectId) {
        return byProject.get(projectId, id -> load(BY_PROJECT, id));
    }

    public ProjectStatus forSource(UUID sourceId) {
        return bySource.get(sourceId, id -> load(BY_SOURCE, id));
    }

    private ProjectStatus load(String sql, UUID id) {
        List<ProjectStatus> found = jdbc.query(sql, (rs, row) -> {
            // Deletion wins: it is final, so there is no point deferring until a suspension lifts.
            if (rs.getBoolean("deleted")) {
                return ProjectStatus.DELETED;
            }
            return rs.getBoolean("suspended") ? ProjectStatus.ORGANIZATION_SUSPENDED : ProjectStatus.ACTIVE;
        }, id);
        // No row is not a deletion: the api never hard-deletes a parent that is still referenced.
        return found.isEmpty() ? ProjectStatus.ACTIVE : found.get(0);
    }
}
