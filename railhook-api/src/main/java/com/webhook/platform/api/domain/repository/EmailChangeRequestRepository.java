package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.EmailChangeRequest;
import com.webhook.platform.api.domain.enums.EmailChangeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailChangeRequestRepository extends JpaRepository<EmailChangeRequest, UUID> {

    Optional<EmailChangeRequest> findByUserIdAndStatus(UUID userId, EmailChangeStatus status);

    Optional<EmailChangeRequest> findByTokenHashAndStatus(String tokenHash, EmailChangeStatus status);

    Optional<EmailChangeRequest> findByCancelTokenHashAndStatus(String cancelTokenHash, EmailChangeStatus status);

    /** Counts every request whatever became of it: the cap is on attempts. */
    long countByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    /** Settled rows past any cap window. Pending ones stay until they are settled. */
    @Modifying
    @Query("delete from EmailChangeRequest r where r.status <> com.webhook.platform.api.domain.enums.EmailChangeStatus.PENDING "
            + "and r.createdAt < :cutoff")
    int deleteSettledBefore(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from EmailChangeRequest r where r.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
