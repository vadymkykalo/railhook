package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.BillingPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingPaymentRepository extends JpaRepository<BillingPayment, UUID> {

    List<BillingPayment> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<BillingPayment> findByInvoiceIdOrderByCreatedAtDesc(UUID invoiceId);

    Optional<BillingPayment> findByExternalPaymentId(String externalPaymentId);
}
