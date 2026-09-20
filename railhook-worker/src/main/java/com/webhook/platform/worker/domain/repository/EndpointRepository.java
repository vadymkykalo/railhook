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
     * Extends the current run of failures, starting one if there is none.
     *
     * <p>Native and unconditional so that two workers failing against the same endpoint at the
     * same time both count: read-modify-write through the entity would have the later write
     * overwrite the earlier one, and the run would grow at the rate of one worker.
     *
     * <p>{@code updated_at} is deliberately left alone — a run of failures is not somebody
     * editing the endpoint, and the dashboard shows that column as "last changed".
     */
    @Modifying
    @Query(value = """
            UPDATE endpoints
               SET consecutive_failures = consecutive_failures + 1,
                   failing_since = COALESCE(failing_since, :at)
             WHERE id = :endpointId
            """, nativeQuery = true)
    int recordAttemptFailed(@Param("endpointId") UUID endpointId, @Param("at") Instant at);

    /**
     * Ends the run. The {@code WHERE} clause is what makes the healthy path free: an endpoint
     * that has never failed matches nothing, so a working deployment writes to this table
     * exactly as often as its endpoints recover, and not once per delivery.
     */
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
