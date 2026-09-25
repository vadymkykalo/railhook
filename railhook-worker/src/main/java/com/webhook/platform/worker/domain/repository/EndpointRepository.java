package com.webhook.platform.worker.domain.repository;

import com.webhook.platform.worker.domain.entity.Endpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

    /**
     * A single UPDATE so concurrent workers both count; read-modify-write loses increments.
     * {@code updated_at} is left alone because the dashboard shows it as "last changed".
     */
    @Modifying
    @Query(value = """
            UPDATE endpoints
               SET consecutive_failures = consecutive_failures + 1,
                   failing_since = COALESCE(failing_since, :at)
             WHERE id = :endpointId
            """, nativeQuery = true)
    int recordAttemptFailed(@Param("endpointId") UUID endpointId, @Param("at") Instant at);

    /** The WHERE clause keeps the healthy path free of writes. */
    @Modifying
    @Query(value = """
            UPDATE endpoints
               SET consecutive_failures = 0,
                   failing_since = NULL
             WHERE id = :endpointId
               AND (failing_since IS NOT NULL OR consecutive_failures <> 0)
            """, nativeQuery = true)
    int recordAttemptSucceeded(@Param("endpointId") UUID endpointId);
}
