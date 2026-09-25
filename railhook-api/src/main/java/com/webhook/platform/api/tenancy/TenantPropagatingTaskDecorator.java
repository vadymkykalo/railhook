package com.webhook.platform.api.tenancy;

import org.springframework.core.task.TaskDecorator;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Carries the submitting thread's tenant scope onto the thread that runs the task, since
 * {@link TenantContext} is a {@code ThreadLocal}. A submission with no scope propagates nothing,
 * so the task fails loudly instead of reading another tenant's rows.
 *
 * <p>A {@code TaskDecorator} only reaches Spring-built executors; a hand-built pool must go
 * through {@link #wrap(ExecutorService)}.
 */
public class TenantPropagatingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return propagate(runnable);
    }

    /** Lifecycle calls and the delegate's rejection policy pass straight through. */
    public static ExecutorService wrap(ExecutorService delegate) {
        return new TenantPropagatingExecutorService(delegate);
    }

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

    /** Decorating {@code execute} is enough: the other submit forms all route through it. */
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
