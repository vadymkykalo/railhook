package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.BillingPayment;
import com.webhook.platform.api.domain.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingPaymentRepository extends JpaRepository<BillingPayment, UUID> {

    List<BillingPayment> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<BillingPayment> findByInvoiceIdOrderByCreatedAtDesc(UUID invoiceId);

    /**
     * The newest payment for a provider reference in one of the given states. A reference can
     * name more than one row — a WayForPay order declined once and then paid holds a FAILED and a
     * SUCCEEDED payment — so a refund asks for the one that took money.
     */
    Optional<BillingPayment> findFirstByProviderCodeAndExternalPaymentIdAndStatusInOrderByCreatedAtDesc(
            String providerCode, String externalPaymentId, Collection<PaymentStatus> statuses);

    boolean existsByProviderCodeAndExternalPaymentIdAndStatusIn(
            String providerCode, String externalPaymentId, Collection<PaymentStatus> statuses);
}
