package com.webhook.platform.api.tenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The propagation contract every pool has to honour, including the two built by hand.
 *
 * <p>{@code AsyncConfig} hands its executors the decorator as a Spring {@code TaskDecorator}, but
 * a pool built with {@code Executors.new*} has no such hook — {@link
 * TenantPropagatingTaskDecorator#wrap} is that hook, and these cases pin the behaviour the two
 * hand-built pools had each re-implemented differently (one of the copies had no counterpart to
 * the {@code captured == null} pass-through, so an unscoped submission got {@code null} written
 * into the ThreadLocal instead of nothing).
 *
 * <p>Deliberately a plain {@code *Test}: threads and ThreadLocals only, no Spring context and no
 * container (see {@code scripts/check-test-routing.sh}).
 */
class TenantPropagatingTaskDecoratorTest {

    private static final UUID ORG = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private ExecutorService pool;

    @AfterEach
    void shutdownPool() {
        if (pool != null) {
            pool.shutdownNow();
        }
        TenantContext.clear();
    }

    @Test
    @DisplayName("a wrapped pool runs execute() tasks in the submitter's tenant")
    void wrapPropagatesOnExecute() throws Exception {
        pool = TenantPropagatingTaskDecorator.wrap(Executors.newSingleThreadExecutor());
        AtomicReference<UUID> seen = new AtomicReference<>();

        TenantContext.set(ORG);
        pool.execute(() -> seen.set(TenantContext.current()));
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(seen.get()).isEqualTo(ORG);
    }

    @Test
    @DisplayName("submit(Callable) propagates too, and the value comes back")
    void wrapPropagatesOnSubmit() throws Exception {
        pool = TenantPropagatingTaskDecorator.wrap(Executors.newSingleThreadExecutor());

        TenantContext.set(ORG);
        Future<UUID> result = pool.submit(TenantContext::current);

        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(ORG);
    }

    @Test
    @DisplayName("system scope propagates like any other, so a scheduler's pool sees root")
    void wrapPropagatesSystemScope() throws Exception {
        pool = TenantPropagatingTaskDecorator.wrap(Executors.newSingleThreadExecutor());

        UUID seen = TenantContext.callAsSystem(() -> {
            try {
                return pool.submit(TenantContext::current).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(seen).isEqualTo(TenantContext.SYSTEM);
    }

    @Test
    @DisplayName("an unscoped submission propagates nothing rather than writing null into the ThreadLocal")
    void wrapLeavesTheWorkerThreadUnscopedWhenTheSubmitterIs() throws Exception {
        pool = TenantPropagatingTaskDecorator.wrap(Executors.newSingleThreadExecutor());

        // Prime the worker thread with a scope, then submit from a thread that has none. The
        // pass-through must not overwrite it with null — the point is that "no scope captured"
        // and "scope of null" are different things, and only the first is what an unscoped
        // submitter means.
        pool.submit(() -> TenantContext.set(ORG)).get(5, TimeUnit.SECONDS);

        TenantContext.clear();
        assertThat(pool.submit(TenantContext::current).get(5, TimeUnit.SECONDS)).isEqualTo(ORG);
    }

    @Test
    @DisplayName("the worker thread's previous scope is restored after the task")
    void wrapRestoresThePreviousScope() throws Exception {
        pool = TenantPropagatingTaskDecorator.wrap(Executors.newSingleThreadExecutor());
        UUID other = UUID.fromString("44444444-4444-4444-4444-444444444444");

        pool.submit(() -> TenantContext.set(other)).get(5, TimeUnit.SECONDS);

        TenantContext.set(ORG);
        pool.submit(TenantContext::current).get(5, TimeUnit.SECONDS);

        TenantContext.clear();
        assertThat(pool.submit(TenantContext::current).get(5, TimeUnit.SECONDS)).isEqualTo(other);
    }

    @Test
    @DisplayName("the delegate's rejection policy still fires — wrapping does not swallow saturation")
    void wrapPreservesTheRejectionPolicy() {
        ThreadPoolExecutor saturated = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        pool = TenantPropagatingTaskDecorator.wrap(saturated);

        Runnable block = () -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        pool.execute(block);   // occupies the single thread
        pool.execute(block);   // fills the single queue slot

        assertThatThrownBy(() -> pool.execute(block)).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @DisplayName("shutdown and awaitTermination reach the delegate")
    void wrapDelegatesLifecycle() throws Exception {
        ThreadPoolExecutor delegate = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        pool = TenantPropagatingTaskDecorator.wrap(delegate);

        pool.shutdown();

        assertThat(pool.isShutdown()).isTrue();
        assertThat(delegate.isShutdown()).isTrue();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pool.isTerminated()).isTrue();
    }

    @Test
    @DisplayName("propagate(Callable) carries the scope for a task handed to a pool nobody owns")
    void propagateCallableCarriesTheScope() throws Exception {
        TenantContext.set(ORG);
        Callable<UUID> task = TenantPropagatingTaskDecorator.propagate(TenantContext::current);

        TenantContext.clear();
        assertThat(task.call()).isEqualTo(ORG);
        assertThat(TenantContext.current()).isNull();
    }
}
