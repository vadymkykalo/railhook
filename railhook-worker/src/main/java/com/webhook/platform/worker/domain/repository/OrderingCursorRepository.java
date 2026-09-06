package com.webhook.platform.worker.domain.repository;

import com.webhook.platform.worker.domain.entity.OrderingCursor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface OrderingCursorRepository extends JpaRepository<OrderingCursor, UUID> {

    /**
     * Upserts the cursor atomically and returns the authoritative post-upsert value.
     *
     * <p>Unlike a WHERE-guarded upsert, this always applies {@code GREATEST} and always
     * returns a row via {@code RETURNING} — including when {@code sequence} did not advance
     * the cursor — so callers (see {@code OrderingBufferService#markDelivered}) can use the
     * return value as the single source of truth to CAS the Redis cache, instead of trusting
     * whatever (possibly stale, possibly flushed) value Redis happens to hold. {@code
     * updated_at} only moves when the cursor itself actually advances, preserving "when this
     * cursor was last advanced" semantics.
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
