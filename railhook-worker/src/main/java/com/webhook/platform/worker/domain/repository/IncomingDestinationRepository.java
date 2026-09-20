package com.webhook.platform.worker.domain.repository;

import com.webhook.platform.worker.domain.entity.IncomingDestination;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface IncomingDestinationRepository extends JpaRepository<IncomingDestination, UUID> {

    List<IncomingDestination> findByIncomingSourceIdAndEnabledTrue(UUID incomingSourceId);

    /** @see EndpointRepository#recordAttemptFailed — the same statement on the other target. */
    @Modifying
    @Query(value = """
            UPDATE incoming_destinations
               SET consecutive_failures = consecutive_failures + 1,
                   failing_since = COALESCE(failing_since, :at)
             WHERE id = :destinationId
            """, nativeQuery = true)
    int recordAttemptFailed(@Param("destinationId") UUID destinationId, @Param("at") Instant at);

    /** @see EndpointRepository#recordAttemptSucceeded */
    @Modifying
    @Query(value = """
            UPDATE incoming_destinations
               SET consecutive_failures = 0,
                   failing_since = NULL
             WHERE id = :destinationId
               AND (failing_since IS NOT NULL OR consecutive_failures <> 0)
            """, nativeQuery = true)
    int recordAttemptSucceeded(@Param("destinationId") UUID destinationId);
}
