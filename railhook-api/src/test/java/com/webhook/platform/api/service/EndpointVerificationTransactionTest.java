package com.webhook.platform.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

// A transactional verify() held a connection through a ten-second wait and drained the pool.
class EndpointVerificationTransactionTest {

    private Method verify() {
        return Arrays.stream(EndpointVerificationService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("verify"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("verify(UUID) has been renamed"));
    }

    @Test
    @DisplayName("the method that waits on a customer's server holds no transaction while it waits")
    void verifyIsNotTransactional() {
        assertThat(verify().isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("the two writes around the wait are each their own transaction")
    void theWritesAreTransactionalIndividually() {
        // Self-invoked @Transactional never reaches the proxy, so the writes must go through a template.
        assertThat(Arrays.stream(EndpointVerificationService.class.getDeclaredMethods())
                .map(Method::getName))
                .contains("beginVerificationAttempt", "recordVerificationOutcome");
        assertThat(Arrays.stream(EndpointVerificationService.class.getDeclaredFields())
                .map(f -> f.getType().getSimpleName()))
                .contains("TransactionTemplate");
        assertThat(Arrays.stream(EndpointVerificationService.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Transactional.class))
                .map(Method::getName))
                .doesNotContain("beginVerificationAttempt", "recordVerificationOutcome");
    }
}
