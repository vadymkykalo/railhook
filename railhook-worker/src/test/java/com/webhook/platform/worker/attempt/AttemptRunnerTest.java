package com.webhook.platform.worker.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.retry.RetryLadder;
import com.webhook.platform.common.retry.RetryableStatuses;
import com.webhook.platform.worker.service.CircuitBreakerService;
import com.webhook.platform.worker.service.PayloadTransformException;
import com.webhook.platform.worker.service.ProjectRateLimiterService;
import com.webhook.platform.worker.service.RedisConcurrencyControlService;
import com.webhook.platform.worker.service.RedisRateLimiterService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// Each invariant here was once right in one direction and wrong in the other.
class AttemptRunnerTest {

    private static final long RETRY_AFTER_MAX_SECONDS = 3600;

    private HttpServer server;
    private String baseUrl;

    private ProjectRateLimiterService tenantRateLimiter;
    private RedisRateLimiterService targetRateLimiter;
    private RedisConcurrencyControlService concurrency;
    private CircuitBreakerService circuitBreaker;
    private AttemptRunner runner;
    private RecordingMetrics metrics;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

        tenantRateLimiter = mock(ProjectRateLimiterService.class);
        targetRateLimiter = mock(RedisRateLimiterService.class);
        concurrency = mock(RedisConcurrencyControlService.class);
        circuitBreaker = mock(CircuitBreakerService.class);

        lenient().when(tenantRateLimiter.tryAcquire(any(UUID.class))).thenReturn(true);
        lenient().when(targetRateLimiter.tryAcquire(any(UUID.class), anyInt())).thenReturn(true);
        lenient().when(concurrency.tryAcquireForTenant(any(UUID.class))).thenReturn(true);
        lenient().when(concurrency.tryAcquireForTarget(any(UUID.class))).thenReturn(true);
        lenient().when(circuitBreaker.isCallPermitted(any(UUID.class))).thenReturn(true);

        metrics = new RecordingMetrics();
        // allowPrivateIps = true: the fake server is on loopback.
        runner = new AttemptRunner(tenantRateLimiter, targetRateLimiter, concurrency,
                circuitBreaker, new ObjectMapper(), true, List.of(), RETRY_AFTER_MAX_SECONDS);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // The status decides the Attempt; the stalled body must not.
    private void respondThenStallBody(int status, long stallMillis, String body) {
        server.createContext("/hook", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try {
                Thread.sleep(stallMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } catch (IOException ignored) {
                // The client gave up on the body; that is the point of the test.
            }
        });
    }

    private void respond(int status, String body) {
        server.createContext("/hook", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private void respondWith(int status, String body, String headerName, String headerValue) {
        server.createContext("/hook", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(headerName, headerValue);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    @Nested
    @DisplayName("no successor unless the finalisation applied")
    class SuccessorGating {

        @Test
        @DisplayName("a retryable failure whose finalisation applied queues exactly one successor")
        void appliedRetryQueuesSuccessor() {
            respond(503, "unavailable");
            FakeStore store = new FakeStore(baseUrl);

            runner.run(store, metrics);

            assertEquals(1, store.finalizations.size());
            assertInstanceOf(Finalization.Retry.class, store.finalizations.get(0));
        }

        @Test
        @DisplayName("a finalisation that did not apply queues nothing and abandons nothing")
        void refusedFinalisationQueuesNothing() {
            respond(503, "unavailable");
            FakeStore store = new FakeStore(baseUrl);
            store.finaliseApplies = false; // the row was reclaimed while we were sending

            runner.run(store, metrics);

            assertEquals(1, store.finalizations.size(), "it must still try exactly once");
            assertEquals(0, store.abandonedCalls, "a refused finalisation must not trigger the DLQ side effect");
            assertEquals(0, store.succeededCalls);
        }

        @Test
        @DisplayName("a success whose finalisation did not apply does not run the success side effect")
        void refusedSuccessDoesNotReleaseOrdering() {
            respond(200, "ok");
            FakeStore store = new FakeStore(baseUrl);
            store.finaliseApplies = false;

            runner.run(store, metrics);

            assertEquals(0, store.succeededCalls,
                    "releasing the ordering buffer for a row we no longer own would let a successor through early");
        }
    }

    @Nested
    @DisplayName("a terminal failure releases what it was holding")
    class TerminalRelease {

        @Test
        @DisplayName("a non-retryable status runs the abandon side effect, which releases the cursor")
        void nonRetryableStatusReleases() {
            respond(422, "unprocessable");
            FakeStore store = new FakeStore(baseUrl);

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Abandoned.class, store.finalizations.get(0));
            // A 4xx that ended TerminallyFailed released nothing, and an ordered endpoint's cursor stuck forever.
            assertEquals(1, store.abandonedCalls,
                    "nothing else ever releases the ordering cursor for this delivery");
            assertEquals(0, store.terminallyFailedCalls);
        }

        @Test
        @DisplayName("an SSRF rejection runs the terminal side effect")
        void ssrfRejectionReleases() {
            // A private address the URL validator refuses before admission.
            FakeStore store = new FakeStore("http://169.254.169.254/latest/meta-data");

            runner.run(store, metrics);

            assertInstanceOf(Finalization.TerminallyFailed.class, store.finalizations.get(0));
            assertEquals(1, store.terminallyFailedCalls);
        }

        @Test
        @DisplayName("a host that does not resolve is retried on the ladder, not failed for good")
        void unresolvableHostIsRetried() {
            // Not resolving says nothing about where the name points; only a refused address is final.
            FakeStore store = new FakeStore("https://no-such-host.invalid/hook");

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Retry.class, store.finalizations.get(0));
            assertEquals(0, store.terminallyFailedCalls);
            assertEquals(1, store.records.size(), "the failed lookup must leave a trace");
            assertNull(store.records.get(0).statusCode());
            assertFalse(store.records.get(0).errorMessage().contains("SSRF_PROTECTION"),
                    "an unresolvable name is not a refused target and must not be reported as one");
        }

        @Test
        @DisplayName("an unresolvable host spends a rung, so the ladder still ends in the DLQ")
        void unresolvableHostSpendsARungAndAbandonsOnTheLast() {
            // The lookup was a real failed Attempt; without the rung the ladder never exhausts.
            FakeStore store = new FakeStore("https://no-such-host.invalid/hook");
            store.attemptNumber = 3;
            store.ladder = RetryLadder.parse("60", 3);

            runner.run(store, metrics);

            assertEquals(1, store.attemptStartingCalls);
            assertInstanceOf(Finalization.Abandoned.class, store.finalizations.get(0));
            assertEquals(1, store.abandonedCalls);
            verify(concurrency, never()).tryAcquireForTenant(any(UUID.class));
        }

        @Test
        @DisplayName("a non-retryable status whose finalisation did not apply releases nothing")
        void refusedTerminalReleasesNothing() {
            respond(422, "unprocessable");
            FakeStore store = new FakeStore(baseUrl);
            store.finaliseApplies = false; // reclaimed by a stuck sweep while we were sending

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Abandoned.class, store.finalizations.get(0));
            assertEquals(0, store.abandonedCalls,
                    "releasing the cursor for a row another attempt now owns would let a "
                            + "successor through early — the same invariant as success");
            assertEquals(0, store.terminallyFailedCalls);
        }
    }

    @Nested
    @DisplayName("classification")
    class Classification {

        @Test
        @DisplayName("2xx succeeds and runs the success side effect once")
        void success() {
            respond(200, "ok");
            FakeStore store = new FakeStore(baseUrl);

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Succeeded.class, store.finalizations.get(0));
            assertEquals(1, store.succeededCalls);
            assertEquals(1, metrics.successes);
            assertEquals(200, store.records.get(0).statusCode());
        }

        @Test
        @DisplayName("a 2xx whose body is larger than the codec limit is still a success")
        void oversizedSuccessBodyIsStillASuccess() {
            // A 200 with a body too big to buffer once ran the whole ladder: seven arrivals.
            // The status is read first, so a huge body is a failure to read, never to deliver.
            respond(200, "x".repeat(2 * 1024 * 1024));
            FakeStore store = new FakeStore(baseUrl);

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Succeeded.class, store.finalizations.get(0));
            assertEquals(1, store.succeededCalls);
            assertEquals(200, store.records.get(0).statusCode());
        }

        @Test
        @DisplayName("a 2xx whose body arrives after the timeout is still a success")
        void stalledSuccessBodyIsStillASuccess() {
            // The outer timeout cancels the inner chain, so no onErrorResume inside sees a stalled body.
            FakeStore store = new FakeStore(baseUrl);
            store.timeoutSeconds = 1;
            respondThenStallBody(200, 3_000, "late");

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Succeeded.class, store.finalizations.get(0));
            assertEquals(1, store.succeededCalls);
            assertEquals(200, store.records.get(0).statusCode());
        }

        @Test
        @DisplayName("a store that cannot persist a 2xx does not turn it into a retry")
        void unpersistableSuccessIsNotRetried() {
            // A DB blip while recording a success once re-sent the webhook; finalising must still run.
            respond(200, "ok");
            FakeStore store = new FakeStore(baseUrl);
            store.recordAttemptFailure = new IllegalStateException("connection pool exhausted");

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Succeeded.class, store.finalizations.get(0),
                    "the receiver has the event; losing the audit row does not change that");
            assertEquals(1, store.succeededCalls);
            assertEquals(1, metrics.successes);
            assertEquals(0, store.abandonedCalls);
        }

        @Test
        @DisplayName("a failure whose finalisation throws is recorded once and left to the stuck sweep")
        void unfinalisableFailureIsRecordedOnce() {
            // A DB blip while recording a 503 once re-entered fail() and recorded the Attempt twice.
            respond(503, "unavailable");
            FakeStore store = new FakeStore(baseUrl);
            store.finaliseFailure = new IllegalStateException("connection pool exhausted");

            assertDoesNotThrow(() -> runner.run(store, metrics));

            assertEquals(1, store.records.size(), "one Attempt was made, so one is recorded");
            assertEquals(503, store.records.get(0).statusCode());
            assertEquals(1, store.finalizations.size(), "the row is the stuck sweep's now, not a second write's");
            assertEquals(1, metrics.failures);
            assertEquals(0, metrics.errors);
        }

        @Test
        @DisplayName("a failed transformation whose finalisation throws does not escape the Runner")
        void unfinalisableTransformFailureDoesNotEscape() {
            respond(200, "ok");
            FakeStore store = new FakeStore(baseUrl);
            store.bodyFailure = new PayloadTransformException("template missing");
            store.finaliseFailure = new IllegalStateException("connection pool exhausted");

            assertDoesNotThrow(() -> runner.run(store, metrics));

            assertEquals(1, store.records.size());
            assertEquals(1, store.finalizations.size());
        }

        // A 4xx or 3xx won't change on retry but a person can fix it, so it goes to the DLQ.
        @Test
        @DisplayName("a non-retryable 4xx goes to DLQ at once instead of burning the ladder")
        void nonRetryableClientErrorAbandonsWithoutTheLadder() {
            respond(404, "not found");
            FakeStore store = new FakeStore(baseUrl);
            store.attemptNumber = 1;
            store.ladder = RetryLadder.parse("60,300", 5);

            runner.run(store, metrics);

            assertEquals(1, store.finalizations.size());
            assertInstanceOf(Finalization.Abandoned.class, store.finalizations.get(0));
            assertEquals(1, store.abandonedCalls, "the DLQ side effect runs, as it does for an exhausted ladder");
            assertEquals(0, store.terminallyFailedCalls);
            assertEquals(1, metrics.failures);
        }

        @Test
        @DisplayName("a redirect goes to DLQ at once: redirects are not followed")
        void redirectAbandonsWithoutTheLadder() {
            respond(301, "");
            FakeStore store = new FakeStore(baseUrl);

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Abandoned.class, store.finalizations.get(0));
            assertEquals(1, store.abandonedCalls);
        }

        @Test
        @DisplayName("a retryable status on the last rung abandons to DLQ and runs the abandon side effect")
        void lastRungAbandons() {
            respond(500, "boom");
            FakeStore store = new FakeStore(baseUrl);
            store.attemptNumber = 3;
            store.ladder = RetryLadder.parse("60", 3); // exhausted at attempt 3

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Abandoned.class, store.finalizations.get(0));
            assertEquals(1, store.abandonedCalls);
        }

        @Test
        @DisplayName("every attempt is recorded, including the ones that never reached the network")
        void attemptsAreAlwaysRecorded() {
            FakeStore store = new FakeStore("http://169.254.169.254/latest/meta-data/");

            runner.run(store, metrics);

            assertEquals(1, store.records.size());
            assertTrue(store.records.get(0).errorMessage().contains("SSRF_PROTECTION"));
            assertNull(store.records.get(0).statusCode());
        }
    }

    @Nested
    @DisplayName("admission")
    class Admission {

        @Test
        @DisplayName("a tenant rate limit defers without consuming an attempt or sending anything")
        void tenantRateLimitDefers() {
            FakeStore store = new FakeStore(baseUrl);
            when(tenantRateLimiter.tryAcquire(any(UUID.class))).thenReturn(false);

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Deferred.class, store.finalizations.get(0));
            assertEquals(0, store.attemptStartingCalls, "a deferral is not an attempt");
            assertEquals(0, store.records.size());
        }

        @Test
        @DisplayName("an open circuit breaker defers, and records the reason so the gap is explicable")
        void circuitBreakerDefersAndRecords() {
            FakeStore store = new FakeStore(baseUrl);
            when(circuitBreaker.isCallPermitted(any(UUID.class))).thenReturn(false);

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Deferred.class, store.finalizations.get(0));
            assertEquals("CIRCUIT_BREAKER_OPEN", store.records.get(0).errorMessage());
        }

        @Test
        @DisplayName("a concurrency cap defers and takes no permit to release")
        void concurrencyCapDefers() {
            FakeStore store = new FakeStore(baseUrl);
            when(concurrency.tryAcquireForTarget(any(UUID.class))).thenReturn(false);

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Deferred.class, store.finalizations.get(0));
            assertEquals(0, store.attemptStartingCalls);
        }

        @Test
        @DisplayName("a blocked URL is terminal and is rejected before any permit is taken")
        void blockedUrlIsTerminalAndCostsNoPermit() {
            FakeStore store = new FakeStore("http://169.254.169.254/latest/meta-data/");

            runner.run(store, metrics);

            assertInstanceOf(Finalization.TerminallyFailed.class, store.finalizations.get(0));
            assertEquals(0, store.attemptStartingCalls);
            verify(concurrency, never()).tryAcquireForTenant(any(UUID.class));
            verify(concurrency, never()).tryAcquireForTarget(any(UUID.class));
        }
    }

    @Nested
    @DisplayName("one tenant cannot take the whole worker")
    class TenantIsolation {

        @Test
        @DisplayName("a tenant at its concurrency cap defers even though its endpoint is under its own")
        void tenantCapDefersWhileTargetHasRoom() {
            FakeStore store = new FakeStore(baseUrl);
            when(concurrency.tryAcquireForTenant(store.tenantKey)).thenReturn(false);
            when(concurrency.tryAcquireForTarget(any(UUID.class))).thenReturn(true);

            runner.run(store, metrics);

            // Each endpoint is well-behaved; their sum is not, which a per-endpoint cap cannot bound.
            assertInstanceOf(Finalization.Deferred.class, store.finalizations.get(0));
            assertEquals(0, store.attemptStartingCalls, "a deferral is not an attempt");
            assertEquals(0, store.records.size());
        }

        @Test
        @DisplayName("a tenant at its cap does not hold a permit belonging to its endpoint")
        void tenantCapReleasesNothingItDidNotTake() {
            FakeStore store = new FakeStore(baseUrl);
            when(concurrency.tryAcquireForTenant(any(UUID.class))).thenReturn(false);

            runner.run(store, metrics);

            // The tenant permit is taken first, so a refusal there must not take the target's.
            verify(concurrency, never()).tryAcquireForTarget(any(UUID.class));
            verify(concurrency, never()).releaseForTarget(any(UUID.class));
        }

        @Test
        @DisplayName("both permits come back on the ordinary path")
        void bothPermitsReleasedAfterASend() {
            respond(200, "ok");
            FakeStore store = new FakeStore(baseUrl);

            runner.run(store, metrics);

            verify(concurrency).releaseForTenant(store.tenantKey);
            verify(concurrency).releaseForTarget(store.targetKey);
        }

        @Test
        @DisplayName("both permits come back when the attempt throws before the request is built")
        void bothPermitsReleasedWhenBodyFails() {
            FakeStore store = new FakeStore(baseUrl);
            store.bodyFailure = new PayloadTransformException("template is broken");

            runner.run(store, metrics);

            // A throwing transformation must not strand either permit.
            verify(concurrency).releaseForTenant(store.tenantKey);
            verify(concurrency).releaseForTarget(store.targetKey);
        }

        @Test
        @DisplayName("the tenant permit comes back when the target's cap refuses")
        void tenantPermitReleasedWhenTargetCapRefuses() {
            FakeStore store = new FakeStore(baseUrl);
            when(concurrency.tryAcquireForTenant(any(UUID.class))).thenReturn(true);
            when(concurrency.tryAcquireForTarget(any(UUID.class))).thenReturn(false);

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Deferred.class, store.finalizations.get(0));
            verify(concurrency).releaseForTenant(store.tenantKey);
        }

        @Test
        @DisplayName("a rate limit that refuses after the permits gives them back")
        void permitsReleasedWhenARateLimitRefuses() {
            FakeStore store = new FakeStore(baseUrl);
            when(tenantRateLimiter.tryAcquire(any(UUID.class))).thenReturn(false);

            runner.run(store, metrics);

            // Permits come before rate limits because a permit can be returned and a token cannot.
            assertInstanceOf(Finalization.Deferred.class, store.finalizations.get(0));
            verify(concurrency).releaseForTenant(store.tenantKey);
            verify(concurrency).releaseForTarget(store.targetKey);
        }

        @Test
        @DisplayName("a refused admission consumes no rate-limit token")
        void refusedAdmissionSpendsNoToken() {
            FakeStore store = new FakeStore(baseUrl);
            when(concurrency.tryAcquireForTenant(any(UUID.class))).thenReturn(false);

            runner.run(store, metrics);

            // Tokens spent before the concurrency check cost the tenant budget for attempts never made.
            verify(tenantRateLimiter, never()).tryAcquire(any(UUID.class));
            verify(targetRateLimiter, never()).tryAcquire(any(UUID.class), anyInt());
        }
    }

    @Nested
    @DisplayName("a failed transformation never lets the raw payload out")
    class TransformFailure {

        @Test
        @DisplayName("it is retryable, nothing is sent, and the raw payload is not in the record")
        void retryableAndNothingSent() {
            respond(200, "ok"); // would succeed if anything were sent
            FakeStore store = new FakeStore(baseUrl);
            store.bodyFailure = new PayloadTransformException("template gone");

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Retry.class, store.finalizations.get(0));
            assertEquals(1, metrics.transformFailures);
            assertEquals(0, metrics.successes, "nothing may be sent");
            assertNull(store.records.get(0).requestBody(),
                    "the raw payload must not be recorded as if it had been sent");
            assertTrue(store.records.get(0).errorMessage().contains("TRANSFORM_FAILED"));
        }

        @Test
        @DisplayName("a failed transformation still consumes a rung")
        void aFailedTransformationStillConsumesARung() {
            // attemptStarting once ran after buildBody, so a pre-send failure retried the same rung for 96h.
            respond(200, "ok");
            FakeStore store = new FakeStore(baseUrl);
            store.bodyFailure = new PayloadTransformException("template gone");

            runner.run(store, metrics);

            assertEquals(1, store.attemptStartingCalls,
                    "a transformation that cannot run is a spent attempt, not a free one");
        }
    }

    @Nested
    @DisplayName("a transformation that cancels ends the obligation without sending")
    class TransformCancelled {

        @Test
        @DisplayName("nothing is sent, nothing is retried, and it is not counted as a failure")
        void nothingIsSentAndNothingIsRetried() {
            respond(200, "ok"); // would succeed if anything were sent
            FakeStore store = new FakeStore(baseUrl);
            store.transformedBody = TransformedBody.cancelled("test traffic");

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Cancelled.class, store.finalizations.get(0),
                    "a cancellation is terminal but is not a failure: the next attempt would run "
                            + "the same script over the same payload and reach the same answer, "
                            + "and nothing went wrong");
            assertEquals(0, metrics.successes, "nothing may be sent");
            assertEquals(0, metrics.transformFailures, "a cancellation is not a failed transform");
            assertEquals(1, metrics.transformCancellations);
        }

        @Test
        @DisplayName("it is recorded, with the reason, so the Delivery is not silently empty")
        void itIsRecordedWithTheReason() {
            respond(200, "ok");
            FakeStore store = new FakeStore(baseUrl);
            store.transformedBody = TransformedBody.cancelled("test traffic");

            runner.run(store, metrics);

            assertEquals(1, store.records.size());
            assertTrue(store.records.get(0).errorMessage().contains("CANCELLED_BY_TRANSFORMATION"));
            assertTrue(store.records.get(0).errorMessage().contains("test traffic"));
            assertNull(store.records.get(0).statusCode(), "nothing was asked, so there is no status");
        }

        @Test
        @DisplayName("whatever the obligation held is released")
        void whateverItHeldIsReleased() {
            // A terminal outcome that does not release the ordering cursor silently stalls the endpoint.
            respond(200, "ok");
            FakeStore store = new FakeStore(baseUrl);
            store.transformedBody = TransformedBody.cancelled(null);

            runner.run(store, metrics);

            assertEquals(1, store.cancelledCalls);
            assertEquals(0, store.abandonedCalls, "a cancellation is not for a human to look at");
            assertEquals(0, store.terminallyFailedCalls, "and it is not a terminal failure either");
        }

        @Test
        @DisplayName("it still costs the rung the script already spent")
        void itStillCostsTheRung() {
            respond(200, "ok");
            FakeStore store = new FakeStore(baseUrl);
            store.transformedBody = TransformedBody.cancelled("test traffic");

            runner.run(store, metrics);

            assertEquals(1, store.attemptStartingCalls);
        }
    }

    @Nested
    @DisplayName("claim outcomes")
    class Claiming {

        @Test
        @DisplayName("NotClaimed does nothing at all")
        void notClaimedDoesNothing() {
            FakeStore store = new FakeStore(baseUrl);
            store.claimResult = new ClaimResult.NotClaimed<>("somebody else owns it");

            runner.run(store, metrics);

            assertTrue(store.finalizations.isEmpty());
            assertTrue(store.records.isEmpty());
        }

        @Test
        @DisplayName("Deferred does nothing further — the store already stamped the row")
        void deferredDoesNothingFurther() {
            FakeStore store = new FakeStore(baseUrl);
            store.claimResult = new ClaimResult.Deferred<>(Instant.now().plusSeconds(5), "ordering");

            runner.run(store, metrics);

            assertTrue(store.finalizations.isEmpty(),
                    "the store finalised it as part of claiming; the Runner must not write again");
            assertEquals(0, store.attemptStartingCalls);
        }
    }

    // The retryable set comes off the obligation only, so the two directions cannot diverge.
    @Nested
    @DisplayName("the retryable statuses the obligation carries")
    class RetryableStatusSpec {

        @Test
        @DisplayName("a status the spec excludes goes to DLQ instead of burning the ladder")
        void excludedStatusIsNotRetried() {
            respond(503, "unavailable");
            FakeStore store = new FakeStore(baseUrl);
            store.retryableStatuses = RetryableStatuses.parse("500-599,!503");

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Abandoned.class, store.finalizations.get(0));
            assertEquals(1, store.abandonedCalls);
        }

        @Test
        @DisplayName("a status the spec includes is retried even though the old set did not")
        void includedStatusIsRetried() {
            respond(409, "conflict");
            FakeStore store = new FakeStore(baseUrl);
            store.retryableStatuses = RetryableStatuses.parse("408,409,429,5xx");

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Retry.class, store.finalizations.get(0));
            assertEquals(0, store.abandonedCalls);
        }

        @Test
        @DisplayName("the default spec still behaves exactly as the hardcoded set did")
        void defaultSpecIsUnchangedBehaviour() {
            respond(500, "boom");
            FakeStore store = new FakeStore(baseUrl);

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Retry.class, store.finalizations.get(0));
        }
    }

    @Nested
    @DisplayName("Retry-After")
    class RetryAfterHeader {

        // A first tier of a minute, so an honoured header is unmistakable.
        private FakeStore storeWithMinuteLadder() {
            FakeStore store = new FakeStore(baseUrl);
            store.ladder = RetryLadder.parse("60,300", 5);
            store.attemptNumber = 1;
            return store;
        }

        private Instant retryAt(FakeStore store) {
            return ((Finalization.Retry) store.finalizations.get(0)).at();
        }

        @Test
        @DisplayName("a 429 asking for an hour is retried in about an hour, not in a minute")
        void honouredOn429() {
            respondWith(429, "slow down", "Retry-After", "3600");
            FakeStore store = storeWithMinuteLadder();

            runner.run(store, metrics);

            Instant at = retryAt(store);
            assertTrue(at.isAfter(Instant.now().plusSeconds(3000)),
                    "the receiver asked for an hour and got the ladder's minute instead: " + at);
        }

        @Test
        @DisplayName("a 503 asking for an hour is honoured too")
        void honouredOn503() {
            respondWith(503, "maintenance", "Retry-After", "3600");
            FakeStore store = storeWithMinuteLadder();

            runner.run(store, metrics);

            assertTrue(retryAt(store).isAfter(Instant.now().plusSeconds(3000)));
        }

        @Test
        @DisplayName("a 500 carrying the header keeps the ladder — the header means something else there")
        void ignoredOn500() {
            respondWith(500, "boom", "Retry-After", "3600");
            FakeStore store = storeWithMinuteLadder();

            runner.run(store, metrics);

            assertTrue(retryAt(store).isBefore(Instant.now().plusSeconds(200)),
                    "a 500 is not a rate limit, so its Retry-After is not ours to honour");
        }

        @Test
        @DisplayName("a header shorter than the ladder does not shorten it")
        void neverShortensTheLadder() {
            respondWith(429, "slow down", "Retry-After", "1");
            FakeStore store = storeWithMinuteLadder();

            runner.run(store, metrics);

            assertTrue(retryAt(store).isAfter(Instant.now().plusSeconds(25)),
                    "a receiver may ask us to wait longer, never to retry harder than configured");
        }

        @Test
        @DisplayName("a header beyond the clamp is cut to the clamp")
        void clamped() {
            respondWith(429, "slow down", "Retry-After", String.valueOf(Duration.ofDays(7).toSeconds()));
            FakeStore store = storeWithMinuteLadder();

            runner.run(store, metrics);

            assertTrue(retryAt(store).isBefore(Instant.now().plusSeconds(RETRY_AFTER_MAX_SECONDS + 60)),
                    "one line of a misconfigured response must not park an obligation for a week");
        }

        @Test
        @DisplayName("a 429 on the last rung still goes to DLQ — the header is not an extra attempt")
        void doesNotExtendTheLadder() {
            respondWith(429, "slow down", "Retry-After", "3600");
            FakeStore store = new FakeStore(baseUrl);
            store.attemptNumber = 3;
            store.ladder = RetryLadder.parse("60", 3);

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Abandoned.class, store.finalizations.get(0));
        }
    }

    // Auto-disable needs a memory that outlives the circuit breaker, so the store writes the outcome.
    @Nested
    @DisplayName("the outcome, as it bears on the target")
    class TargetOutcome {

        @Test
        @DisplayName("a 2xx reports the target healthy")
        void successReportsHealthy() {
            respond(200, "ok");
            FakeStore store = new FakeStore(baseUrl);

            runner.run(store, metrics);

            assertEquals(List.of(true), store.targetOutcomes);
        }

        @Test
        @DisplayName("a retryable failure reports the target failing")
        void retryableFailureReportsFailing() {
            respond(503, "unavailable");
            FakeStore store = new FakeStore(baseUrl);

            runner.run(store, metrics);

            assertEquals(List.of(false), store.targetOutcomes);
        }

        @Test
        @DisplayName("a non-retryable status reports the target failing too — it answered, and badly")
        void nonRetryableFailureReportsFailing() {
            respond(404, "not found");
            FakeStore store = new FakeStore(baseUrl);

            runner.run(store, metrics);

            assertEquals(List.of(false), store.targetOutcomes);
        }

        @Test
        @DisplayName("a request that never produced a response reports the target failing")
        void transportFailureReportsFailing() {
            FakeStore store = new FakeStore("http://127.0.0.1:1/hook");

            runner.run(store, metrics);

            assertEquals(List.of(false), store.targetOutcomes);
        }

        @Test
        @DisplayName("a Deferral reports nothing — nothing was tried, so the target said nothing")
        void deferralReportsNothing() {
            when(circuitBreaker.isCallPermitted(any(UUID.class))).thenReturn(false);
            FakeStore store = new FakeStore(baseUrl);

            runner.run(store, metrics);

            assertTrue(store.targetOutcomes.isEmpty(),
                    "an open breaker means we sent nothing; counting it would disable a target "
                            + "for our own throttling");
        }

        @Test
        @DisplayName("a store that cannot write the outcome down does not turn a 2xx into a retry")
        void unwritableOutcomeDoesNotReclassifyASuccess() {
            respond(200, "ok");
            FakeStore store = new FakeStore(baseUrl);
            store.targetOutcomeFailure = new IllegalStateException("connection pool exhausted");

            assertDoesNotThrow(() -> runner.run(store, metrics));

            assertInstanceOf(Finalization.Succeeded.class, store.finalizations.get(0),
                    "invariant 1: once a 2xx is in hand nothing that goes wrong writing it down "
                            + "may reclassify it");
            assertEquals(1, store.succeededCalls);
        }
    }

    // Records what the Runner asked for: assert through the interface, not either direction's tables.

    @Nested
    @DisplayName("what goes on the wire")
    class Wire {

        private final java.util.concurrent.atomic.AtomicReference<byte[]> receivedBody =
                new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicReference<String> receivedContentType =
                new java.util.concurrent.atomic.AtomicReference<>();

        private void capture() {
            server.createContext("/hook", exchange -> {
                receivedBody.set(exchange.getRequestBody().readAllBytes());
                receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
        }

        // A forward must carry the provider's exact bytes; re-encoding a String altered non-UTF-8 bodies.
        @Test
        @DisplayName("the bytes the store hands over arrive exactly, even when they are not UTF-8")
        void bytesArriveUnchanged() {
            capture();
            FakeStore store = new FakeStore(baseUrl);
            store.contentType = "application/octet-stream";
            store.wireBytes = new byte[] {(byte) 0xC0, (byte) 0xFF, 0x00, 0x41};

            runner.run(store, metrics);

            assertInstanceOf(Finalization.Succeeded.class, store.finalizations.get(0));
            org.junit.jupiter.api.Assertions.assertArrayEquals(store.wireBytes, receivedBody.get());
        }

        @Test
        @DisplayName("the Content-Type the store sets arrives as set, with no charset added")
        void contentTypeArrivesAsSet() {
            capture();
            FakeStore store = new FakeStore(baseUrl);
            store.contentType = "text/plain";

            runner.run(store, metrics);

            assertEquals("text/plain", receivedContentType.get());
        }
    }

    private static final class FakeStore implements AttemptStore<String> {

        private final String url;

        ClaimResult<String> claimResult;
        UUID tenantKey = UUID.randomUUID();
        UUID targetKey = UUID.randomUUID();
        RetryLadder ladder = RetryLadder.parse("60,300", 5);
        RetryableStatuses retryableStatuses = RetryableStatuses.parse(RetryableStatuses.DEFAULT_SPEC);
        int attemptNumber = 1;
        boolean finaliseApplies = true;
        PayloadTransformException bodyFailure;
        int timeoutSeconds = 5;
        RuntimeException recordAttemptFailure;
        RuntimeException finaliseFailure;
        RuntimeException targetOutcomeFailure;
        String contentType;
        byte[] wireBytes;
        TransformedBody transformedBody;

        final List<Finalization> finalizations = new ArrayList<>();
        final List<AttemptRecord> records = new ArrayList<>();
        final List<Boolean> targetOutcomes = new ArrayList<>();
        int attemptStartingCalls;
        int abandonedCalls;
        int succeededCalls;
        int terminallyFailedCalls;
        int cancelledCalls;

        FakeStore(String url) {
            this.url = url;
        }

        @Override
        public ClaimResult<String> claim() {
            if (claimResult != null) {
                return claimResult;
            }
            return new ClaimResult.Claimed<>("claim-1", new AttemptContext(
                    "fake attempt", tenantKey, targetKey, null,
                    attemptNumber, ladder, retryableStatuses, url, timeoutSeconds));
        }

        @Override
        public RequestSpec buildRequest(String claim, TransformedBody transformed) {
            return new RequestSpec(WebClient.builder().build(),
                    request -> {
                        request.header("X-Test", "1");
                        if (contentType != null) {
                            request.header("Content-Type", contentType);
                        }
                    }, "{\"X-Test\":\"1\"}");
        }

        @Override
        public byte[] wireBody(String claim, String body) {
            return wireBytes != null ? wireBytes : AttemptStore.super.wireBody(claim, body);
        }

        @Override
        public TransformedBody buildBody(String claim) {
            if (bodyFailure != null) {
                throw bodyFailure;
            }
            return transformedBody != null ? transformedBody
                    : TransformedBody.of("{\"transformed\":true}");
        }

        @Override
        public void attemptStarting(String claim) {
            attemptStartingCalls++;
        }

        @Override
        public void recordAttempt(String claim, AttemptRecord record) {
            records.add(record);
            if (recordAttemptFailure != null) {
                throw recordAttemptFailure;
            }
        }

        @Override
        public boolean finalise(String claim, Finalization outcome) {
            finalizations.add(outcome);
            if (finaliseFailure != null) {
                throw finaliseFailure;
            }
            return finaliseApplies;
        }

        @Override
        public void onAbandoned(String claim) {
            abandonedCalls++;
        }

        @Override
        public void onSucceeded(String claim) {
            succeededCalls++;
        }

        @Override
        public void recordTargetOutcome(String claim, boolean succeeded) {
            targetOutcomes.add(succeeded);
            if (targetOutcomeFailure != null) {
                throw targetOutcomeFailure;
            }
        }

        @Override
        public void onCancelled(String claim) {
            cancelledCalls++;
        }

        @Override
        public void onTerminallyFailed(String claim) {
            terminallyFailedCalls++;
        }
    }

    private static final class RecordingMetrics implements AttemptMetrics {
        int successes;
        int failures;
        int errors;
        int transformFailures;
        int transformCancellations;

        @Override
        public void success(int statusCode, int durationMs) {
            successes++;
        }

        @Override
        public void failure(int statusCode, int durationMs) {
            failures++;
        }

        @Override
        public void error(int durationMs) {
            errors++;
        }

        @Override
        public void transformFailed() {
            transformFailures++;
        }

        @Override
        public void transformCancelled() {
            transformCancellations++;
        }
    }
}
