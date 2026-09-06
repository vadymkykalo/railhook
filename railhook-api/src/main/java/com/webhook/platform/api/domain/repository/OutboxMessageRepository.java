package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {
    // The outer SELECT..IN(...) FOR UPDATE has its own ORDER BY created_at — without it,
    // Postgres is free to return the id-filtered rows in arbitrary (plan) order even though the
    // inner subquery computed the correct rn_proj/rn_key ranking, so the List<OutboxMessage>
    // handed to OutboxPublisherService#publishBatchAsync could interleave up to maxPerKey
    // messages for the same endpoint out of order.
    @Query(value = """
            SELECT * FROM outbox_messages WHERE id IN (
                SELECT id FROM (
                    SELECT id,
                           ROW_NUMBER() OVER (PARTITION BY kafka_key ORDER BY created_at ASC) AS rn_key,
                           ROW_NUMBER() OVER (PARTITION BY COALESCE(CAST(project_id AS text), kafka_key) ORDER BY created_at ASC) AS rn_proj
                    FROM outbox_messages WHERE status = :status
                ) sub WHERE rn_key <= :maxPerKey AND rn_proj <= :maxPerProject
                ORDER BY rn_proj ASC, rn_key ASC LIMIT :limit
            ) ORDER BY created_at ASC FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> findPendingBatchForUpdate(@Param("status") String status, @Param("limit") int limit, @Param("maxPerKey") int maxPerKey, @Param("maxPerProject") int maxPerProject);

    // Same fix as findPendingBatchForUpdate above — this feeds the same publishBatchAsync path.
    @Query(value = """
            SELECT * FROM outbox_messages WHERE id IN (
                SELECT id FROM (
                    SELECT id,
                           ROW_NUMBER() OVER (PARTITION BY kafka_key ORDER BY created_at ASC) AS rn_key,
                           ROW_NUMBER() OVER (PARTITION BY COALESCE(CAST(project_id AS text), kafka_key) ORDER BY created_at ASC) AS rn_proj
                    FROM outbox_messages WHERE status = :status AND retry_count < :maxRetries
                ) sub WHERE rn_key <= :maxPerKey AND rn_proj <= :maxPerProject
                ORDER BY rn_proj ASC, rn_key ASC LIMIT :limit
            ) ORDER BY created_at ASC FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> findFailedMessagesForRetry(@Param("status") String status, @Param("maxRetries") int maxRetries, @Param("limit") int limit, @Param("maxPerKey") int maxPerKey, @Param("maxPerProject") int maxPerProject);
    
    @Modifying
    @Query(value = "DELETE FROM outbox_messages WHERE id IN (SELECT id FROM outbox_messages WHERE status = :status AND created_at < :cutoffTime ORDER BY created_at ASC LIMIT :limit)", nativeQuery = true)
    int deleteOldPublishedMessages(@Param("status") String status, @Param("cutoffTime") Instant cutoffTime, @Param("limit") int limit);

    long countByStatus(OutboxStatus status);

    @Query(value = "SELECT MIN(created_at) FROM outbox_messages WHERE status = 'PENDING'", nativeQuery = true)
    Instant findOldestPendingCreatedAt();

    @Modifying
    @Query(value = "UPDATE outbox_messages SET status = 'PENDING' WHERE status = 'SENDING' AND updated_at < :cutoff", nativeQuery = true)
    int recoverStuckSendingMessages(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query(value = "UPDATE outbox_messages SET status = 'PUBLISHED', published_at = :now, updated_at = :now WHERE id IN :ids", nativeQuery = true)
    int batchMarkPublished(@Param("ids") List<UUID> ids, @Param("now") Instant now);

    @Modifying
    @Query(value = "UPDATE outbox_messages SET status = 'FAILED', retry_count = retry_count + 1, error_message = :error, last_attempt_at = :now, updated_at = :now WHERE id IN :ids", nativeQuery = true)
    int batchMarkFailed(@Param("ids") List<UUID> ids, @Param("error") String error, @Param("now") Instant now);

    @Modifying
    @Query(value = "UPDATE outbox_messages SET status = 'DEAD', retry_count = retry_count + 1, error_message = :error, last_attempt_at = :now, updated_at = :now WHERE id IN :ids", nativeQuery = true)
    int batchMarkDead(@Param("ids") List<UUID> ids, @Param("error") String error, @Param("now") Instant now);

    @Modifying
    @Query(value = "UPDATE outbox_messages SET status = 'DEAD', updated_at = NOW() WHERE status = 'FAILED' AND retry_count >= :maxRetries", nativeQuery = true)
    int promoteExhaustedToDead(@Param("maxRetries") int maxRetries);
}
