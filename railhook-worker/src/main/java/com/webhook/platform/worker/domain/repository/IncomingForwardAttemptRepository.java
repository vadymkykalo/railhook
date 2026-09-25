package com.webhook.platform.worker.domain.repository;

import com.webhook.platform.common.demo.DemoTenant;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface IncomingForwardAttemptRepository extends JpaRepository<IncomingForwardAttempt, UUID> {

        /** Scoped to the Replay session: a Replay starts a second Ladder for the same pair. */
         @Query("SELECT a FROM IncomingForwardAttempt a WHERE a.incomingEventId = :eventId "
                        + "AND a.destinationId = :destinationId "
                        + "AND ((:replaySessionId IS NULL AND a.replaySessionId IS NULL) "
                        + "OR a.replaySessionId = :replaySessionId) "
                        + "ORDER BY a.attemptNumber DESC")
        List<IncomingForwardAttempt> findForwardAttempts(@Param("eventId") UUID eventId,
                        @Param("destinationId") UUID destinationId,
                        @Param("replaySessionId") UUID replaySessionId);

        @Query(value = """
                        SELECT id FROM (
                            SELECT id, ROW_NUMBER() OVER (PARTITION BY destination_id ORDER BY next_retry_at ASC) AS rn
                            FROM incoming_forward_attempts
                            WHERE status = :#{#status.name()} AND next_retry_at IS NOT NULL AND next_retry_at <= :now
                        ) sub WHERE rn <= :maxPerDest ORDER BY rn ASC LIMIT :limit
                        """, nativeQuery = true)
        List<UUID> findPendingRetryIds(@Param("status") ForwardAttemptStatus status,
                        @Param("now") Instant now,
                        @Param("limit") int limit,
                        @Param("maxPerDest") int maxPerDest);

        @Query(value = "SELECT * FROM incoming_forward_attempts WHERE id IN :ids ORDER BY next_retry_at ASC FOR UPDATE SKIP LOCKED",
                        nativeQuery = true)
        List<IncomingForwardAttempt> lockByIds(@Param("ids") List<UUID> ids);

        /**
         * {@code IS NOT DISTINCT FROM} because an ingress Forward has a null Replay session, which
         * {@code =} never matches. The cast lets a null bind resolve to uuid.
         */
        @Modifying
        @Query(value = "UPDATE incoming_forward_attempts SET status = 'PROCESSING', " +
                        "started_at = now(), claim_token = :claimToken " +
                        "WHERE incoming_event_id = :eventId AND destination_id = :destinationId " +
                        "AND attempt_number = :attemptNumber AND status = 'PENDING' " +
                        "AND replay_session_id IS NOT DISTINCT FROM CAST(:replaySessionId AS uuid)",
                        nativeQuery = true)
        int claimForProcessing(@Param("eventId") UUID eventId,
                        @Param("destinationId") UUID destinationId,
                        @Param("attemptNumber") int attemptNumber,
                        @Param("replaySessionId") UUID replaySessionId,
                        @Param("claimToken") UUID claimToken);

        @Modifying
        // Cleared alongside the status: a swept attempt must not still match its own token.
        @Query(value = "UPDATE incoming_forward_attempts SET status = 'PENDING', " +
                        "next_retry_at = now(), claim_token = NULL " +
                        "WHERE status = 'PROCESSING' AND started_at < :threshold", nativeQuery = true)
        int resetStuckForwardAttempts(@Param("threshold") Instant threshold);

        /**
         * First dispatch and replay insert PENDING rows with no next_retry_at and rely on the Kafka
         * message, so a lost message stranded the Forward. Gated on created_at so a just-ingested
         * Forward is not swept from under its in-flight message.
         */
        @Modifying
        @Query(value = "UPDATE incoming_forward_attempts SET next_retry_at = now() " +
                        "WHERE status = 'PENDING' AND next_retry_at IS NULL AND created_at < :threshold",
                        nativeQuery = true)
        int resetStrandedPendingForwardAttempts(@Param("threshold") Instant threshold);

        /** CAS on the scheduler's stamp, so a redelivered Kafka message updates no rows. */
        @Modifying
        @Query(value = "UPDATE incoming_forward_attempts SET started_at = now(), " +
                        "claim_token = :claimToken " +
                        "WHERE incoming_event_id = :eventId AND destination_id = :destinationId " +
                        "AND attempt_number = :attemptNumber AND status = 'PROCESSING' " +
                        "AND started_at = :expectedStartedAt " +
                        "AND replay_session_id IS NOT DISTINCT FROM CAST(:replaySessionId AS uuid)",
                        nativeQuery = true)
        int claimRetryForProcessing(@Param("eventId") UUID eventId,
                        @Param("destinationId") UUID destinationId,
                        @Param("attemptNumber") int attemptNumber,
                        @Param("replaySessionId") UUID replaySessionId,
                        @Param("expectedStartedAt") Instant expectedStartedAt,
                        @Param("claimToken") UUID claimToken);

        /** Matches nothing once any copy of the retry message has claimed the row. */
        @Modifying
        @Query(value = "UPDATE incoming_forward_attempts SET status = 'PENDING', started_at = NULL, " +
                        "claim_token = NULL, next_retry_at = :retryAt " +
                        "WHERE incoming_event_id = :eventId AND destination_id = :destinationId " +
                        "AND attempt_number = :attemptNumber AND status = 'PROCESSING' " +
                        "AND started_at = :expectedStartedAt " +
                        "AND replay_session_id IS NOT DISTINCT FROM CAST(:replaySessionId AS uuid)",
                        nativeQuery = true)
        int handBackIfStillClaimed(@Param("eventId") UUID eventId,
                        @Param("destinationId") UUID destinationId,
                        @Param("attemptNumber") int attemptNumber,
                        @Param("replaySessionId") UUID replaySessionId,
                        @Param("expectedStartedAt") Instant expectedStartedAt,
                        @Param("retryAt") Instant retryAt);

        /** Matches nothing once any copy of the message has claimed the row. */
        @Modifying
        @Query(value = "UPDATE incoming_forward_attempts SET next_retry_at = :retryAt " +
                        "WHERE incoming_event_id = :eventId AND destination_id = :destinationId " +
                        "AND attempt_number = :attemptNumber AND status = 'PENDING' " +
                        "AND replay_session_id IS NOT DISTINCT FROM CAST(:replaySessionId AS uuid)",
                        nativeQuery = true)
        int scheduleIfUnclaimed(@Param("eventId") UUID eventId,
                        @Param("destinationId") UUID destinationId,
                        @Param("attemptNumber") int attemptNumber,
                        @Param("replaySessionId") UUID replaySessionId,
                        @Param("retryAt") Instant retryAt);

        /**
         * Locks the row for the caller's transaction if it is still held under {@code fence}, so
         * {@code finalise} cannot overwrite a stuck sweep that committed after its read. The no-op
         * UPDATE is what takes the lock and re-evaluates the predicate after a concurrent writer.
         */
        @Modifying
        @Query(value = "UPDATE incoming_forward_attempts SET claim_token = claim_token " +
                        "WHERE id = :id AND status IN (:statuses) " +
                        "AND claim_token IS NOT DISTINCT FROM CAST(:fence AS uuid)", nativeQuery = true)
        int holdIfStillClaimed(@Param("id") UUID id,
                        @Param("statuses") List<String> statuses,
                        @Param("fence") UUID fence);

        /**
         * For a send the scheduler could not confirm. It may still land and hand the row to a
         * consumer, so this must match nothing once a consumer has claimed it.
         */
        @Modifying
        @Query(value = "UPDATE incoming_forward_attempts SET status = 'PENDING', started_at = NULL, " +
                        "claim_token = NULL, next_retry_at = :retryAt " +
                        "WHERE id = :id AND status = 'PROCESSING' AND started_at = :claimedAt " +
                        "AND claim_token IS NULL", nativeQuery = true)
        int handBackSchedulerClaim(@Param("id") UUID id,
                        @Param("claimedAt") Instant claimedAt,
                        @Param("retryAt") Instant retryAt);

        /**
         * Ages a Forward from its attempt 1 in the same Replay session. Each Attempt is a new row,
         * and the event's received_at made a retried or replayed Forward look days old.
         */
        @Query(value = """
                        SELECT MIN(COALESCE(f.created_at, a.created_at)) FROM incoming_forward_attempts a
                        LEFT JOIN incoming_forward_attempts f
                            ON f.incoming_event_id = a.incoming_event_id
                            AND f.destination_id = a.destination_id
                            AND f.attempt_number = 1
                            AND f.replay_session_id IS NOT DISTINCT FROM a.replay_session_id
                        WHERE a.status = 'PENDING'
                        """, nativeQuery = true)
        Instant findOldestPendingForwardStartedAt();

        /**
         * SKIP LOCKED so two replicas do not publish duplicate DLQ notifications. Only {@code a}
         * is locked, as Postgres requires for the outer join.
         */
        @Query(value = """
                        SELECT a.id FROM incoming_forward_attempts a
                        LEFT JOIN incoming_forward_attempts f
                            ON f.incoming_event_id = a.incoming_event_id
                            AND f.destination_id = a.destination_id
                            AND f.attempt_number = 1
                            AND f.replay_session_id IS NOT DISTINCT FROM a.replay_session_id
                        WHERE a.status = 'PENDING' AND COALESCE(f.created_at, a.created_at) < :cutoff
                        ORDER BY COALESCE(f.created_at, a.created_at) ASC
                        LIMIT :limit
                        FOR UPDATE OF a SKIP LOCKED
                        """, nativeQuery = true)
        List<UUID> findStaleForwardAttemptIds(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

        @Query("SELECT COUNT(a) FROM IncomingForwardAttempt a WHERE a.status = 'PENDING' AND a.createdAt > :since")
        long countPending(@Param("since") Instant since);

        @Query("SELECT COUNT(a) FROM IncomingForwardAttempt a WHERE a.status = 'PROCESSING' AND a.createdAt > :since")
        long countProcessing(@Param("since") Instant since);

        @Query("SELECT COUNT(a) FROM IncomingForwardAttempt a WHERE a.status = 'DLQ' AND a.createdAt > :since "
                        + "AND a.organizationId <> :excluded")
        long countDlqExcluding(@Param("since") Instant since, @Param("excluded") UUID excludedOrganizationId);

        /** Excludes the public demo's seeded rows, which would otherwise page the operator. */
        default long countDlq(Instant since) {
                return countDlqExcluding(since, DemoTenant.ORGANIZATION_ID);
        }

        /** Not windowed: a Forward abandoned a week ago still needs a decision. */
        @Query("SELECT COUNT(a) FROM IncomingForwardAttempt a WHERE a.status = 'DLQ' AND a.organizationId <> :excluded")
        long countDlqTotalExcluding(@Param("excluded") UUID excludedOrganizationId);

        default long countDlqTotal() {
                return countDlqTotalExcluding(DemoTenant.ORGANIZATION_ID);
        }
}
