package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification calls a URL the customer chose, and waits up to ten seconds for it.
 *
 * <p>{@code verify} was {@code @Transactional} around that wait, having already dirtied the
 * entity — so each call held a Hikari connection and a row lock on {@code endpoints} for the
 * duration. It is reachable from a user-facing endpoint, so a handful of concurrent
 * verifications against slow targets drained the connection pool for the whole instance, and
 * every other request in the service went with it. The delivery path states the same rule as
 * invariant 1 and keeps to it; this was the one place that did not.
 *
 * <p>Asserted structurally rather than by timing a pool: the fault is not that it was slow, it
 * is that the wait was inside the transaction, and only one of those two is a property the code
 * has.
 */
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
        assertThat(verify().isAnnotationPresent(Transactional.class))
                .as("a ten-second wait on somebody else's server must not hold a database connection")
                .isFalse();
    }

    @Test
    @DisplayName("the two writes around the wait are each their own transaction")
    void theWritesAreTransactionalIndividually() {
        // Reading the endpoint and stamping the attempt is one short transaction; recording the
        // verdict afterwards is another. Neither spans the HTTP call.
        //
        // Through a TransactionTemplate, not @Transactional: verify() calls both on itself, and
        // a self-invocation never reaches the proxy - the annotation would look right and do
        // nothing, which is the same trap in a different costume. OutboxPublisherService says
        // the same thing about itself.
        assertThat(Arrays.stream(EndpointVerificationService.class.getDeclaredMethods())
                .map(Method::getName))
                .contains("beginVerificationAttempt", "recordVerificationOutcome");
        assertThat(Arrays.stream(EndpointVerificationService.class.getDeclaredFields())
                .map(f -> f.getType().getSimpleName()))
                .as("a self-invoked @Transactional is decoration; the template is not")
                .contains("TransactionTemplate");
        assertThat(Arrays.stream(EndpointVerificationService.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Transactional.class))
                .map(Method::getName))
                .doesNotContain("beginVerificationAttempt", "recordVerificationOutcome");
    }

    @Test
    @DisplayName("it does not build a connection pool per call")
    void usesTheSharedWebClient() {
        // HttpClient.create() with no provider gives each invocation its own connection pool,
        // which never gets reused and never gets cleaned up on any schedule the caller controls.
        // The injected builder already carries the SSRF connector.
        assertThat(Arrays.stream(EndpointVerificationService.class.getDeclaredFields())
                .map(f -> f.getType().getSimpleName()))
                .as("the client is built once, at construction")
                .contains("WebClient");
    }

    @Test
    @DisplayName("the repository is still the only way it reaches the database")
    void stillUsesTheRepository() {
        assertThat(Arrays.stream(EndpointVerificationService.class.getDeclaredFields())
                .map(f -> f.getType().getName()))
                .contains(EndpointRepository.class.getName());
        assertThat(Endpoint.class).isNotNull();
    }
}
