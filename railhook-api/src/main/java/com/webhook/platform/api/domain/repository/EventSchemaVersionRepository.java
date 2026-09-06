package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.EventSchemaVersion;
import com.webhook.platform.api.domain.enums.SchemaStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventSchemaVersionRepository extends JpaRepository<EventSchemaVersion, UUID> {
    List<EventSchemaVersion> findByEventTypeIdOrderByVersionDesc(UUID eventTypeId);

    Optional<EventSchemaVersion> findByEventTypeIdAndVersion(UUID eventTypeId, int version);

    Optional<EventSchemaVersion> findByEventTypeIdAndStatus(UUID eventTypeId, SchemaStatus status);

    Optional<EventSchemaVersion> findByEventTypeIdAndFingerprint(UUID eventTypeId, String fingerprint);

    @Query("SELECT COALESCE(MAX(v.version), 0) FROM EventSchemaVersion v WHERE v.eventTypeId = :eventTypeId")
    int findMaxVersionByEventTypeId(@Param("eventTypeId") UUID eventTypeId);

    @Query("SELECT v FROM EventSchemaVersion v WHERE v.eventTypeId = :eventTypeId AND v.status = 'ACTIVE'")
    Optional<EventSchemaVersion> findActiveByEventTypeId(@Param("eventTypeId") UUID eventTypeId);
}
