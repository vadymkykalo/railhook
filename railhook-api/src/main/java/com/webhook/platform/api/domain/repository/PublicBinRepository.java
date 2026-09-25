package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.PublicBin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PublicBinRepository extends JpaRepository<PublicBin, UUID> {

    Optional<PublicBin> findBySlugAndExpiresAtAfter(String slug, Instant now);

    long countByExpiresAtAfter(Instant now);

    long countByCreatorIpAndExpiresAtAfter(String creatorIp, Instant now);

    @Modifying
    @Query("UPDATE PublicBin b SET b.requestCount = b.requestCount + 1 WHERE b.id = :id")
    void incrementRequestCount(@Param("id") UUID id);

    /** Requests go with them through ON DELETE CASCADE. */
    @Modifying
    @Query("DELETE FROM PublicBin b WHERE b.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
