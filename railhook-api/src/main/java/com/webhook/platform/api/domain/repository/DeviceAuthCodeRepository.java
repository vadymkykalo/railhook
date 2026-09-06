package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.DeviceAuthCode;
import com.webhook.platform.api.domain.enums.DeviceAuthStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceAuthCodeRepository extends JpaRepository<DeviceAuthCode, UUID> {

    Optional<DeviceAuthCode> findByDeviceCode(String deviceCode);

    Optional<DeviceAuthCode> findByUserCode(String userCode);

    Optional<DeviceAuthCode> findByUserCodeAndStatus(String userCode, DeviceAuthStatus status);

    @Modifying
    @Query("UPDATE DeviceAuthCode d SET d.status = 'EXPIRED' WHERE d.status = 'PENDING' AND d.expiresAt < :now")
    int expireOldCodes(@Param("now") Instant now);

    /**
     * Compare-and-set: flips an APPROVED code to CONSUMED and reports how many rows
     * moved. The WHERE clause is the CAS guard — under Postgres READ COMMITTED, two
     * concurrent callers serialize on the row; the first to commit wins (returns 1),
     * and the second re-evaluates the WHERE against the now-committed row and finds
     * it no longer APPROVED (returns 0). Callers MUST check the return value rather
     * than assuming success — this is what makes {@code pollDeviceToken} single-use
     * under concurrent polls.
     */
    @Modifying
    @Query("UPDATE DeviceAuthCode d SET d.status = 'CONSUMED' WHERE d.id = :id AND d.status = 'APPROVED'")
    int markConsumedIfApproved(@Param("id") UUID id);
}
