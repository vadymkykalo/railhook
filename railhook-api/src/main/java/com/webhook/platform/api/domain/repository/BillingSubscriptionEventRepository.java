package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.BillingSubscriptionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BillingSubscriptionEventRepository extends JpaRepository<BillingSubscriptionEvent, UUID> {

    List<BillingSubscriptionEvent> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);
}
