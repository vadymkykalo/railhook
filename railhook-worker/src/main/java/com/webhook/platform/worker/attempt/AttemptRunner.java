package com.webhook.platform.worker.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.retry.RetryAfter;
import com.webhook.platform.common.security.UrlValidator;
import com.webhook.platform.common.util.HeaderSanitizer;
import com.webhook.platform.worker.service.CircuitBreakerService;
import com.webhook.platform.worker.service.PayloadTransformException;
import com.webhook.platform.worker.service.ProjectRateLimiterService;
import com.webhook.platform.worker.service.RedisConcurrencyControlService;
import com.webhook.platform.worker.service.RedisRateLimiterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one Attempt for either direction. What differs between them is behind {@link AttemptStore}.
 *
 * <p>Invariants. Each was once broken in one direction:
 *
 * <ol>
 *   <li>No DB, Redis or Kafka work inside the reactive chain. A write there can trip the HTTP
 *       timeout and run the failure path over a SUCCESS already written. After the chain, a
 *       failure to write down a 2xx must not turn it into a retry.</li>
 *   <li>No successor Attempt unless {@link AttemptStore#finalise} reports it wrote.</li>
 *   <li>Every path that takes a concurrency permit releases it, including ones that throw before
 *       the request is built.</li>
 *   <li>A failed transformation never lets the raw payload out.</li>
 *   <li>A Deferral is not an Attempt: it consumes nothing and advances no Ladder. Conversely, an
 *       Attempt that was really made costs a rung even if it failed before the request existed,
 *       or it retries at the same rung until the hard cap.</li>
 *   <li>Failing to read a response body is not failing to deliver. Once the status is in hand
 *       the outcome is decided; a body that is too big or too slow costs only the body.</li>
 * </ol>
 */
@Component
@Slf4j
public class AttemptRunner {

    private final ProjectRateLimiterService tenantRateLimiter;
    private final RedisRateLimiterService targetRateLimiter;
    private final RedisConcurrencyControlService concurrencyControl;
    private final CircuitBreakerService circuitBreaker;
    private final ObjectMapper objectMapper;
    private final boolean allowPrivateIps;
    private final List<String> allowedHosts;
    private final Duration retryAfterMax;

    public AttemptRunner(
            ProjectRateLimiterService tenantRateLimiter,
            RedisRateLimiterService targetRateLimiter,
            RedisConcurrencyControlService concurrencyControl,
            CircuitBreakerService circuitBreaker,
            ObjectMapper objectMapper,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") List<String> allowedHosts,
            @Value("${webhook.retry-after.max-seconds:21600}") long retryAfterMaxSeconds) {
        this.tenantRateLimiter = tenantRateLimiter;
        this.targetRateLimiter = targetRateLimiter;
        this.concurrencyControl = concurrencyControl;
        this.circuitBreaker = circuitBreaker;
        this.objectMapper = objectMapper;
        this.allowPrivateIps = allowPrivateIps;
        this.allowedHosts = allowedHosts;
        this.retryAfterMax = Duration.ofSeconds(Math.max(0, retryAfterMaxSeconds));
    }

    public <C> void run(AttemptStore<C> store, AttemptMetrics metrics) {
        ClaimResult<C> result = store.claim();

        if (result instanceof ClaimResult.NotClaimed<C> notClaimed) {
            log.debug("Nothing to attempt: {}", notClaimed.reason());
        } else if (result instanceof ClaimResult.Deferred<C> deferred) {
            log.debug("Deferred until {}: {}", deferred.until(), deferred.reason());
        } else if (result instanceof ClaimResult.Claimed<C> held) {
            attempt(store, metrics, held.claim(), held.context());
        } else {
            throw new IllegalStateException("Unhandled claim result: " + result);
        }
    }

    private <C> void attempt(AttemptStore<C> store, AttemptMetrics metrics, C claim, AttemptContext ctx) {
        long startedAt = System.currentTimeMillis();

        try {
            UrlValidator.validateWebhookUrl(ctx.url(), allowPrivateIps, allowedHosts);
        } catch (UrlValidator.UnresolvableHostException e) {
            // DNS failure is retryable, unlike a refused address. It used to fail the obligation
            // for good after one bad minute of DNS. It costs a rung (invariant 5) but no permit.
            String reason = "DNS_RESOLUTION_FAILED: " + e.getMessage();
            log.warn("{}: {}", ctx.description(), reason);
            try {
                store.attemptStarting(claim);
            } catch (Exception startFailure) {
                log.error("{}: could not spend the rung for a failed lookup: {}",
                        ctx.description(), startFailure.getMessage(), startFailure);
            }
            fail(store, metrics, claim, ctx, reason, null, null, elapsed(startedAt));
            return;
        } catch (UrlValidator.InvalidUrlException e) {
            String reason = "SSRF_PROTECTION: " + e.getMessage();
            log.error("{}: {}", ctx.description(), reason);
            recordQuietly(store, claim, ctx, errorRecord(null, null, reason, elapsed(startedAt)));
            terminallyFail(store, claim, reason);
            return;
        }

        if (!admit(store, claim, ctx)) {
            return;
        }

        String requestHeaders = null;
        String body = null;
        try {
            // Spend the rung before anything that can throw. It used to be spent after the request
            // was built, so a deleted transformation or a broken mTLS client left the attempt
            // number stuck: isExhausted never became true and the delivery retried every minute
            // until the 96h hard cap, writing thousands of attempt rows. Invariant 5.
            store.attemptStarting(claim);

            TransformedBody transformed = store.buildBody(claim);

            if (transformed.cancelled()) {
                // Terminal: the same script over the same payload gives the same answer. Recorded
                // so the Delivery shows why nothing went out. The breaker is not touched because
                // the target was never called.
                String reason = "CANCELLED_BY_TRANSFORMATION: " + (transformed.cancelReason() == null
                        ? "no reason given" : transformed.cancelReason());
                log.info("{}: {}", ctx.description(), reason);
                metrics.transformCancelled();
                recordQuietly(store, claim, ctx, errorRecord(null, null, reason, elapsed(startedAt)));
                cancel(store, claim, ctx, reason);
                return;
            }

            body = transformed.body();

            RequestSpec spec = store.buildRequest(claim, transformed);
            requestHeaders = spec.recordedHeaders();

            Response response = send(spec, ctx, store.wireBody(claim, body));

            if (response == null) {
                fail(store, metrics, claim, ctx, "Empty response from " + ctx.url(),
                        requestHeaders, body, elapsed(startedAt));
                return;
            }

            classify(store, metrics, claim, ctx, response, requestHeaders, body, elapsed(startedAt));

        } catch (PayloadTransformException e) {
            // Retryable, so a template fixed in time still gets the webhook out.
            metrics.transformFailed();
            String reason = "TRANSFORM_FAILED: " + e.getMessage();
            log.error("{}: refusing to send the raw payload: {}", ctx.description(), reason);
            fail(store, metrics, claim, ctx, reason, requestHeaders, null, elapsed(startedAt));
        } catch (Exception e) {
            log.error("{}: request failed: {}", ctx.description(), e.getMessage());
            fail(store, metrics, claim, ctx, e.getMessage(), requestHeaders, body, elapsed(startedAt));
        } finally {
            concurrencyControl.releaseForTarget(ctx.targetKey());
            concurrencyControl.releaseForTenant(ctx.tenantKey());
        }
    }

    /**
     * Permits are taken before rate-limit tokens because only permits can be handed back. The
     * tenant cap stops one organization's slow receivers from taking the whole worker.
     */
    private <C> boolean admit(AttemptStore<C> store, C claim, AttemptContext ctx) {
        if (!circuitBreaker.isCallPermitted(ctx.targetKey())) {
            // Recorded though nothing was sent: a quiet target should show the breaker.
            recordQuietly(store, claim, ctx, errorRecord(null, null, "CIRCUIT_BREAKER_OPEN", 0));
            return defer(store, claim, ctx, "circuit breaker open", Instant.now().plusSeconds(30));
        }

        if (!concurrencyControl.tryAcquireForTenant(ctx.tenantKey())) {
            return defer(store, claim, ctx, 2, 60, "tenant concurrency limit reached");
        }

        if (!concurrencyControl.tryAcquireForTarget(ctx.targetKey())) {
            concurrencyControl.releaseForTenant(ctx.tenantKey());
            return defer(store, claim, ctx, 2, 60, "target concurrency limit reached");
        }

        if (!tenantRateLimiter.tryAcquire(ctx.tenantKey())) {
            releaseBothPermits(ctx);
            return defer(store, claim, ctx, 1, 30, "tenant rate limit exceeded");
        }

        Integer perTarget = ctx.targetRateLimitPerSecond();
        if (perTarget != null && !targetRateLimiter.tryAcquire(ctx.targetKey(), perTarget)) {
            releaseBothPermits(ctx);
            return defer(store, claim, ctx, 2, 60, "target rate limit exceeded");
        }

        return true;
    }

    private void releaseBothPermits(AttemptContext ctx) {
        concurrencyControl.releaseForTarget(ctx.targetKey());
        concurrencyControl.releaseForTenant(ctx.tenantKey());
    }

    /** CANCELLED, not FAILED: nothing went wrong, so analytics count it as neither. */
    private <C> void cancel(AttemptStore<C> store, C claim, AttemptContext ctx, String reason) {
        if (store.finalise(claim, new Finalization.Cancelled(reason))) {
            store.onCancelled(claim);
        } else {
            log.warn("{}: the cancellation did not apply — the obligation is owned by another "
                    + "attempt now, so nothing was released: {}", ctx.description(), reason);
        }
    }

    private <C> void terminallyFail(AttemptStore<C> store, C claim, String reason) {
        if (store.finalise(claim, new Finalization.TerminallyFailed(reason))) {
            store.onTerminallyFailed(claim);
        } else {
            log.warn("terminal finalisation did not apply — the obligation is owned by another "
                    + "attempt now, so nothing was released: {}", reason);
        }
    }

    private <C> void abandon(AttemptStore<C> store, C claim, AttemptContext ctx, String reason) {
        if (finaliseOrLeaveToSweep(store, claim, ctx, new Finalization.Abandoned(reason))) {
            store.onAbandoned(claim);
        } else {
            log.warn("{}: abandon did not apply — the obligation is owned by another attempt now: {}",
                    ctx.description(), reason);
        }
    }

    /** A throw is left to the stuck sweep; propagating it recorded the Attempt twice. */
    private <C> boolean finaliseOrLeaveToSweep(AttemptStore<C> store, C claim, AttemptContext ctx,
            Finalization outcome) {
        try {
            return store.finalise(claim, outcome);
        } catch (Exception e) {
            log.error("{}: the outcome would not finalise: {} — the obligation stays claimed and the "
                    + "stuck sweep owns it", ctx.description(), e.getMessage(), e);
            return false;
        }
    }

    private <C> boolean defer(AttemptStore<C> store, C claim, AttemptContext ctx,
            long baseSeconds, long maxSeconds, String reason) {
        long delay = RetryPolicy.backoffWithJitter(ctx.attemptNumber(), baseSeconds, maxSeconds);
        return defer(store, claim, ctx, reason, Instant.now().plusSeconds(delay));
    }

    private <C> boolean defer(AttemptStore<C> store, C claim, AttemptContext ctx,
            String reason, Instant until) {
        log.warn("{}: {}, deferring until {}", ctx.description(), reason, until);
        store.finalise(claim, new Finalization.Deferred(until, reason));
        return false;
    }

    /** Bytes, not a String: WebClient re-encodes a String with the Content-Type charset. */
    private Response send(RequestSpec spec, AttemptContext ctx, byte[] body) {
        WebClient.RequestBodySpec request = spec.client().post().uri(ctx.url());
        spec.headers().accept(request);

        // Invariant 6. A timeout during the body read cancels the inner chain instead of failing
        // it, so no onErrorResume sees it. The status is stashed as soon as the head arrives.
        AtomicInteger statusSeen = new AtomicInteger(-1);
        AtomicReference<String> headersSeen = new AtomicReference<>("{}");
        AtomicReference<String> retryAfterSeen = new AtomicReference<>();

        Mono<Response> exchange = request.bodyValue(body != null ? body : new byte[0])
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    HttpHeaders responseHeaders = response.headers().asHttpHeaders();
                    String headers = serialiseHeaders(responseHeaders);
                    String retryAfter = responseHeaders.getFirst(HttpHeaders.RETRY_AFTER);
                    statusSeen.set(status);
                    headersSeen.set(headers);
                    retryAfterSeen.set(retryAfter);
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(responseBody -> new Response(status, responseBody, headers, retryAfter))
                            .onErrorResume(e -> Mono.just(
                                    unreadableBody(status, headers, retryAfter, e.getMessage())));
                })
                .timeout(Duration.ofSeconds(ctx.timeoutSeconds()));

        try {
            return exchange.block();
        } catch (RuntimeException e) {
            int status = statusSeen.get();
            if (status < 0) {
                throw e;
            }
            // A receiver that answered 2xx and then stalled on the body used to get every retry.
            log.warn("{}: HTTP {} received, but the response body did not: {}",
                    ctx.description(), status, e.getMessage());
            return unreadableBody(status, headersSeen.get(), retryAfterSeen.get(), e.getMessage());
        }
    }

    private Response unreadableBody(int status, String headers, String retryAfter, String why) {
        return new Response(status, "[response body unreadable: " + why + "]", headers, retryAfter);
    }

    private <C> void classify(AttemptStore<C> store, AttemptMetrics metrics, C claim,
            AttemptContext ctx, Response response, String requestHeaders, String body, int durationMs) {
        int status = response.status();
        AttemptRecord record = new AttemptRecord(status, response.body(), response.headers(),
                requestHeaders, body, null, durationMs);

        if (status >= 200 && status < 300) {
            metrics.success(status, durationMs);
            circuitBreaker.recordSuccess(ctx.targetKey(), durationMs);
            recordTargetOutcome(store, claim, ctx, true);
            // These writes used to sit inside the caller's catch-all, so a DB blip here resent
            // a webhook the receiver already had.
            recordQuietly(store, claim, ctx, record);
            try {
                if (store.finalise(claim, new Finalization.Succeeded())) {
                    store.onSucceeded(claim);
                }
            } catch (Exception e) {
                log.error("{}: delivered, but the success would not finalise: {} — the obligation "
                        + "stays claimed and the stuck sweep owns it", ctx.description(), e.getMessage(), e);
            }
            return;
        }

        metrics.failure(status, durationMs);
        recordQuietly(store, claim, ctx, record);
        recordTargetOutcome(store, claim, ctx, false);

        if (ctx.retryableStatuses().isRetryable(status)) {
            circuitBreaker.recordFailure(ctx.targetKey(), new RuntimeException("HTTP " + status));
            retryOrAbandon(store, claim, ctx, "Retryable HTTP " + status, response.retryAfter(), status);
        } else {
            // Abandoned (DLQ), not FAILED: retrying will not fix a 4xx, but a person can, and
            // FAILED obligations are not offered for retry in the UI.
            circuitBreaker.recordFailure(ctx.targetKey(), new RuntimeException("Non-retryable HTTP " + status));
            abandon(store, claim, ctx, "Non-retryable HTTP " + status);
        }
    }

    private <C> void fail(AttemptStore<C> store, AttemptMetrics metrics, C claim, AttemptContext ctx,
            String errorMessage, String requestHeaders, String body, int durationMs) {
        metrics.error(durationMs);
        circuitBreaker.recordFailure(ctx.targetKey(), new RuntimeException(String.valueOf(errorMessage)));
        recordQuietly(store, claim, ctx, errorRecord(requestHeaders, body, errorMessage, durationMs));
        recordTargetOutcome(store, claim, ctx, false);
        retryOrAbandon(store, claim, ctx, errorMessage, null, -1);
    }

    /** Swallows failures (invariant 1): a counter that won't increment must not undo a 2xx. */
    private <C> void recordTargetOutcome(AttemptStore<C> store, C claim, AttemptContext ctx,
            boolean succeeded) {
        try {
            store.recordTargetOutcome(claim, succeeded);
        } catch (Exception e) {
            log.error("{}: the target's outcome could not be recorded: {}",
                    ctx.description(), e.getMessage(), e);
        }
    }

    /** {@code statusCode} is -1 when there was no response. */
    private <C> void retryOrAbandon(AttemptStore<C> store, C claim, AttemptContext ctx, String reason,
            String retryAfterHeader, int statusCode) {
        if (ctx.ladder().isExhausted(ctx.attemptNumber())) {
            log.warn("{}: ladder exhausted after {} attempts, abandoning: {}",
                    ctx.description(), ctx.attemptNumber(), reason);
            abandon(store, claim, ctx, "Max attempts reached: " + reason);
            return;
        }

        // Retry-After can only push the Ladder's time later, up to the clamp. It is not an extra
        // Attempt, so it applies after the isExhausted check.
        Instant next = RetryAfter.nextRetryAt(ctx.ladder().nextRetryAt(ctx.attemptNumber()),
                retryAfterHeader, statusCode, Instant.now(), retryAfterMax);
        if (finaliseOrLeaveToSweep(store, claim, ctx, new Finalization.Retry(next, reason))) {
            log.info("{}: attempt {} failed ({}), next at {}",
                    ctx.description(), ctx.attemptNumber(), reason, next);
        } else {
            log.warn("{}: finalisation did not apply — the obligation is owned by another "
                    + "attempt now, so no successor was queued", ctx.description());
        }
    }

    /** Losing the record is acceptable; a propagated insert failure re-entered fail(). */
    private <C> void recordQuietly(AttemptStore<C> store, C claim, AttemptContext ctx, AttemptRecord record) {
        try {
            store.recordAttempt(claim, record);
        } catch (Exception e) {
            log.error("{}: the attempt could not be recorded: {}", ctx.description(), e.getMessage(), e);
        }
    }

    private AttemptRecord errorRecord(String requestHeaders, String body, String errorMessage, int durationMs) {
        return new AttemptRecord(null, null, null, requestHeaders, body, errorMessage, durationMs);
    }

    private int elapsed(long startedAt) {
        return (int) (System.currentTimeMillis() - startedAt);
    }

    private String serialiseHeaders(HttpHeaders headers) {
        try {
            Map<String, String> flattened = new HashMap<>();
            headers.forEach((key, values) -> {
                if (values != null && !values.isEmpty()) {
                    flattened.put(key, values.get(0));
                }
            });
            return objectMapper.writeValueAsString(HeaderSanitizer.sanitize(flattened));
        } catch (Exception e) {
            log.warn("Failed to serialise response headers: {}", e.getMessage());
            return "{}";
        }
    }

    /** {@code headers} is sanitised JSON for display; {@code retryAfter} is the raw value to parse. */
    private record Response(int status, String body, String headers, String retryAfter) {
    }
}
