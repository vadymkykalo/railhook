package com.webhook.platform.worker.domain.repository;

import com.webhook.platform.worker.domain.entity.OrderingCursor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface OrderingCursorRepository extends JpaRepository<OrderingCursor, UUID> {

    /**
     * Always returns the post-upsert cursor, even when it did not advance, so callers can CAS
     * Redis from the database value instead of trusting a stale or flushed cache.
     * {@code updated_at} moves only when the cursor advances.
     */
    @Query(value = """
        INSERT INTO ordering_cursors (endpoint_id, last_delivered_sequence, updated_at)
        VALUES (:endpointId, :sequence, CURRENT_TIMESTAMP)
        ON CONFLICT (endpoint_id)
        DO UPDATE SET
            last_delivered_sequence = GREATEST(ordering_cursors.last_delivered_sequence, :sequence),
            updated_at = CASE
                WHEN GREATEST(ordering_cursors.last_delivered_sequence, :sequence) > ordering_cursors.last_delivered_sequence
                THEN CURRENT_TIMESTAMP
                ELSE ordering_cursors.updated_at
            END
        RETURNING last_delivered_sequence
        """, nativeQuery = true)
    long upsertCursor(@Param("endpointId") UUID endpointId, @Param("sequence") long sequence);
}
