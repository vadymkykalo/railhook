package com.webhook.platform.api.tenancy;

import org.springframework.core.task.TaskDecorator;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Carries the submitting thread's tenant scope onto the thread that runs the task.
 *
 * <p>{@link TenantContext} is a {@code ThreadLocal}, so an {@code @Async} method would otherwise
 * start with no scope at all and fail on its first query. Every {@code @Async} method here is
 * work handed off from a request that already knows whose data it is — dispatching an alert for
 * a rule, running a replay session, triggering workflows for a project — so inheriting the
 * caller's tenant is both the safe answer and the accurate one.
 *
 * <p>An {@code @Async} call made from a system-scoped path inherits {@link TenantContext#SYSTEM}
 * the same way, which is what a scheduler handing work to a pool wants. A submission from a
 * thread with no scope at all propagates nothing, and the task fails the way any unscoped code
 * does — loudly, rather than by quietly reading another tenant's rows.
 *
 * <h2>Pools Spring does not build</h2>
 *
 * <p>{@code TaskDecorator} is a Spring hook, so it reaches only the executors declared in
 * {@code AsyncConfig}. A pool built with {@code Executors.new*} or {@code new ThreadPoolExecutor}
 * has no such hook, and both of the ones in this codebase had grown their own answer to the same
 * problem — one wrapping each task body in {@code runAs}, the other re-implementing this class
 * inline, already missing the {@code captured == null} pass-through. {@link #wrap(ExecutorService)}
 * is the shared answer: same semantics, applied to every task the pool ever runs, whichever
 * {@code submit}/{@code execute}/{@code invokeAll} form the caller used.
 *
 * <p>{@code HandBuiltExecutorTenantPropagationTest} is the ratchet that keeps a third copy from
 * appearing.
 */
public class TenantPropagatingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return propagate(runnable);
    }

    /**
     * The same propagation as {@link #decorate}, as a static so a hand-built pool can use it.
     *
     * <p>Every task submitted through the returned service runs in the scope its submitter was in,
     * and the worker thread's own scope is restored afterwards. Lifecycle calls
     * ({@code shutdown}, {@code awaitTermination}, …) and the delegate's rejection policy pass
     * straight through, so a pool keeps its saturation behaviour and its shutdown semantics —
     * which is why the two hand-built pools are wrapped where they are rather than moved into
     * {@code AsyncConfig}: each exists for a reason that a {@code ThreadPoolTaskExecutor} bean
     * would flatten.
     */
    public static ExecutorService wrap(ExecutorService delegate) {
        return new TenantPropagatingExecutorService(delegate);
    }

    /** Captures the current scope now and re-enters it when the task runs. */
    public static Runnable propagate(Runnable task) {
        UUID captured = TenantContext.current();
        return () -> {
            if (captured == null) {
                task.run();
                return;
            }
            UUID previous = TenantContext.set(captured);
            try {
                task.run();
            } finally {
                TenantContext.restore(previous);
            }
        };
    }

    /** {@link #propagate(Runnable)} for work that returns a value or throws. */
    public static <T> Callable<T> propagate(Callable<T> task) {
        UUID captured = TenantContext.current();
        return () -> {
            if (captured == null) {
                return task.call();
            }
            UUID previous = TenantContext.set(captured);
            try {
                return task.call();
            } finally {
                TenantContext.restore(previous);
            }
        };
    }

    /**
     * Decorates on {@code execute} alone, which is enough: {@link AbstractExecutorService} routes
     * {@code submit}, {@code invokeAll} and {@code invokeAny} through it, so there is no form of
     * hand-off that can slip past the decoration.
     */
    private static final class TenantPropagatingExecutorService extends AbstractExecutorService {

        private final ExecutorService delegate;

        private TenantPropagatingExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(propagate(command));
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
