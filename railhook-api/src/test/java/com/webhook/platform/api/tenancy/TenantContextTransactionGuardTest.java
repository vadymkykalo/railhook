package com.webhook.platform.api.tenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guard over the most-repeated tenancy footgun: entering a tenant scope after the
 * transaction has already opened.
 *
 * <p>Hibernate reads the tenant when it opens the session, so a scope entered inside an active
 * transaction arrives too late — the row is stamped with whatever scope was in effect when the
 * transaction began. The declarative path is already safe by construction
 * ({@link SystemTenantAspect} runs at {@code HIGHEST_PRECEDENCE}, outside {@code @Transactional});
 * the imperative path had nothing but a comment, and the failure is silent.
 *
 * <p>Deliberately a plain {@code *Test}: pure {@code ThreadLocal} manipulation, no Spring context
 * and no container, so it belongs in the no-Docker unit job.
 */
class TenantContextTransactionGuardTest {

    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @AfterEach
    void resetThreadState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TenantContext.clear();
    }

    @Test
    @DisplayName("callAs inside an open transaction fails instead of stamping the wrong organization")
    void callAsInsideTransactionThrows() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> TenantContext.callAs(ORG, () -> "never runs"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the transaction");
    }

    @Test
    @DisplayName("runAs inside an open transaction fails the same way")
    void runAsInsideTransactionThrows() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> TenantContext.runAs(ORG, () -> { }))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the guard rejects before the body runs and leaves the previous scope untouched")
    void guardDoesNotEnterTheScope() {
        UUID caller = UUID.fromString("22222222-2222-2222-2222-222222222222");
        TenantContext.set(caller);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        boolean[] bodyRan = {false};
        assertThatThrownBy(() -> TenantContext.runAs(ORG, () -> bodyRan[0] = true))
                .isInstanceOf(IllegalStateException.class);

        assertThat(bodyRan[0]).isFalse();
        assertThat(TenantContext.current()).isEqualTo(caller);
    }

    @Test
    @DisplayName("outside a transaction the scope is entered and restored as before")
    void callAsOutsideTransactionStillWorks() {
        assertThat(TenantContext.callAs(ORG, TenantContext::current)).isEqualTo(ORG);
        assertThat(TenantContext.current()).isNull();
    }

    @Test
    @DisplayName("system scope is not guarded — root adds no predicate, so it cannot mis-stamp a row")
    void systemScopeInsideTransactionIsAllowed() {
        TenantContext.set(ORG);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatCode(() -> TenantContext.runAsSystem(() -> { })).doesNotThrowAnyException();
        assertThat(TenantContext.current()).isEqualTo(ORG);
    }
}
