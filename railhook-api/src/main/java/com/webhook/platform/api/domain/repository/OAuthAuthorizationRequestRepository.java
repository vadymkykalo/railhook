package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.OAuthAuthorizationRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OAuthAuthorizationRequestRepository extends JpaRepository<OAuthAuthorizationRequest, UUID> {

    /** Locked, so a double-clicked Approve answers the request once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM OAuthAuthorizationRequest r WHERE r.id = :id")
    Optional<OAuthAuthorizationRequest> findForUpdate(@Param("id") UUID id);

    @Modifying
    @Query("DELETE FROM OAuthAuthorizationRequest r WHERE r.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
