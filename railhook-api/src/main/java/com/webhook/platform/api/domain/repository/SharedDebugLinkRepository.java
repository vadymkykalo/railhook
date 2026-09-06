package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.SharedDebugLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SharedDebugLinkRepository extends JpaRepository<SharedDebugLink, UUID> {
    Optional<SharedDebugLink> findByToken(String token);
    List<SharedDebugLink> findByProjectId(UUID projectId);
    List<SharedDebugLink> findByEventId(UUID eventId);

    @Modifying
    @Query("UPDATE SharedDebugLink s SET s.viewCount = s.viewCount + 1 WHERE s.token = :token")
    void incrementViewCount(@Param("token") String token);
}
