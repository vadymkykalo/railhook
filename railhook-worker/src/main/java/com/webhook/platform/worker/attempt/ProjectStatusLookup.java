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
 * Whether the Project an Endpoint or a Source belongs to may still be sent for: the api deletes a
 * Project by stamping it, and suspends an Organization the same way, and neither touches the
 * Endpoints, Sources or Destinations underneath. Without asking this, a Delivery or Forward
 * already queued when either happened went on being sent for the rest of its Ladder.
 *
 * <p>Native, because the worker keeps no entity for {@code projects}, {@code organizations} or
 * {@code incoming_sources} — the only thing it reads from them is this. Unscoped by design: the
 * worker has no tenant of its own, and the id comes off a row it has already claimed.
 *
 * <p>Asked once per Attempt, so cached briefly per id. A deletion or a suspension takes effect
 * within the TTL, which is the window the api's own suspension cache already allows ingest.
 */
@Component
public class ProjectStatusLookup {

    /** Short enough that a lifted suspension is noticed on the first Attempt after it. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final long MAX_CACHED = 10_000;

    /**
     * How long a Delivery or Forward of a suspended Organization is handed back for. A suspension
     * is lifted by a person, so nothing about it is urgent to notice; what matters is that a
     * backlog of thousands is not re-claimed every few seconds for as long as it stands. A
     * suspension that outlasts the hard cap ends in the DLQ, where it can still be retried.
     */
    public static final Duration SUSPENSION_RECHECK = Duration.ofMinutes(5);

    public enum ProjectStatus {
        ACTIVE,
        /** The Project was deleted. Nothing brings a deleted Project back. */
        DELETED,
        /** Its Organization is suspended, which an operator can lift. */
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

    /** The status of the Project an Endpoint belongs to. */
    public ProjectStatus forProject(UUID projectId) {
        return byProject.get(projectId, id -> load(BY_PROJECT, id));
    }

    /** The status of the Project a Source — and so its Destinations — belongs to. */
    public ProjectStatus forSource(UUID sourceId) {
        return bySource.get(sourceId, id -> load(BY_SOURCE, id));
    }

    private ProjectStatus load(String sql, UUID id) {
        List<ProjectStatus> found = jdbc.query(sql, (rs, row) -> {
            // Deleted first: a deletion is final, and deferring it until a suspension lifts
            // would only send nothing later instead of now.
            if (rs.getBoolean("deleted")) {
                return ProjectStatus.DELETED;
            }
            return rs.getBoolean("suspended") ? ProjectStatus.ORGANIZATION_SUSPENDED : ProjectStatus.ACTIVE;
        }, id);
        // No row is not a deletion: the api hard-deletes nothing a live Endpoint or Source still
        // references, and a missing parent is reported by whatever finds the child missing.
        return found.isEmpty() ? ProjectStatus.ACTIVE : found.get(0);
    }
}
