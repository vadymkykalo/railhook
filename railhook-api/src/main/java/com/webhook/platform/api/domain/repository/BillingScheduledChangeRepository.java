package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.BillingScheduledChange;
import com.webhook.platform.api.domain.enums.ScheduledChangeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingScheduledChangeRepository extends JpaRepository<BillingScheduledChange, UUID> {

    Optional<BillingScheduledChange> findBySubscriptionIdAndStatus(UUID subscriptionId, ScheduledChangeStatus status);

    List<BillingScheduledChange> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    @Query("SELECT sc FROM BillingScheduledChange sc WHERE sc.status = 'PENDING' AND sc.effectiveAt <= :now")
    List<BillingScheduledChange> findReadyToApply(@Param("now") Instant now);
}
