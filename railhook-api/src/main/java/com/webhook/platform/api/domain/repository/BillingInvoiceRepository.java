package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.BillingInvoice;
import com.webhook.platform.api.domain.enums.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingInvoiceRepository extends JpaRepository<BillingInvoice, UUID> {

    List<BillingInvoice> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<BillingInvoice> findByExternalInvoiceId(String externalInvoiceId);

    List<BillingInvoice> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);

    @Query("SELECT i FROM BillingInvoice i WHERE i.status = :status AND i.dueDate < :now")
    List<BillingInvoice> findOverdue(@Param("status") InvoiceStatus status, @Param("now") Instant now);
}
