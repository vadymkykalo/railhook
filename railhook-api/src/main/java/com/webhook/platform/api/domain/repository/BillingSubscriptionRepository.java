package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.BillingSubscription;
import com.webhook.platform.api.domain.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingSubscriptionRepository extends JpaRepository<BillingSubscription, UUID> {

    Optional<BillingSubscription> findByOrganizationIdAndStatusIn(UUID organizationId, List<SubscriptionStatus> statuses);

    default Optional<BillingSubscription> findActiveByOrganizationId(UUID organizationId) {
        return findByOrganizationIdAndStatusIn(organizationId,
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING, SubscriptionStatus.PAST_DUE, SubscriptionStatus.GRACE_PERIOD));
    }

    List<BillingSubscription> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<BillingSubscription> findByExternalSubscriptionId(String externalSubscriptionId);

    /**
     * A provider customer outlives its subscriptions, so the newest row is the one a callback
     * without a subscription id is about.
     */
    Optional<BillingSubscription> findFirstByExternalCustomerIdOrderByCreatedAtDesc(String externalCustomerId);

    List<BillingSubscription> findByOrganizationIdAndStatus(UUID organizationId, SubscriptionStatus status);

    Optional<BillingSubscription> findFirstByOrganizationIdAndProviderCodeAndExternalCustomerIdIsNotNullOrderByCreatedAtDesc(
            UUID organizationId, String providerCode);

    @Query("SELECT s FROM BillingSubscription s WHERE s.status = :status AND s.currentPeriodEnd < :now")
    List<BillingSubscription> findExpiredByStatus(@Param("status") SubscriptionStatus status, @Param("now") Instant now);

    // Fetches the plan: the renewal scheduler reads it outside a transaction.
    @Query("SELECT s FROM BillingSubscription s JOIN FETCH s.plan WHERE s.status = 'ACTIVE' AND s.currentPeriodEnd < :now AND s.providerCode = :providerCode")
    List<BillingSubscription> findDueForRenewal(@Param("now") Instant now, @Param("providerCode") String providerCode);

    @Query("SELECT s FROM BillingSubscription s WHERE s.status = 'GRACE_PERIOD' AND s.currentPeriodEnd < :graceCutoff")
    List<BillingSubscription> findGracePeriodExpired(@Param("graceCutoff") Instant graceCutoff);

    // Fetches the plan: reconciliation compares plan names outside a transaction.
    @Query("SELECT s FROM BillingSubscription s JOIN FETCH s.plan WHERE s.providerCode = :providerCode " +
           "AND s.externalSubscriptionId IS NOT NULL " +
           "AND s.status IN ('ACTIVE', 'PAST_DUE', 'GRACE_PERIOD')")
    List<BillingSubscription> findReconcilable(@Param("providerCode") String providerCode);
}
