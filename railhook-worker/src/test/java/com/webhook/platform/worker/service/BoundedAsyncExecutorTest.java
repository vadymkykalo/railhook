package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.slf4j.MDC;
import org.springframework.kafka.support.Acknowledgment;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BoundedAsyncExecutorTest {

    private BoundedAsyncExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new BoundedAsyncExecutor("test", 4, 5, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void trySubmit_shouldRunTaskAndAck() throws Exception {
        Acknowledgment ack = mock(Acknowledgment.class);
        CountDownLatch latch = new CountDownLatch(1);

        boolean accepted = executor.trySubmit(latch::countDown, ack, "test-1");

        assertTrue(accepted);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        verify(ack).acknowledge();
    }

    @Test
    void trySubmit_shouldNotAckOnFailure() throws Exception {
        Acknowledgment ack = mock(Acknowledgment.class);
        CountDownLatch latch = new CountDownLatch(1);

        boolean accepted = executor.trySubmit(() -> {
            latch.countDown();
            throw new RuntimeException("boom");
        }, ack, "test-fail");

        assertTrue(accepted);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        verify(ack, never()).acknowledge();
    }

    @Test
    void trySubmit_shouldReturnFalseWhenFull() throws Exception {
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch allStarted = new CountDownLatch(4);
        Acknowledgment ack = mock(Acknowledgment.class);

        for (int i = 0; i < 4; i++) {
            assertTrue(executor.trySubmit(() -> {
                allStarted.countDown();
                try { blockLatch.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, ack, "fill-" + i));
        }

        assertTrue(allStarted.await(5, TimeUnit.SECONDS));

        Acknowledgment fifthAck = mock(Acknowledgment.class);
        boolean accepted = executor.trySubmit(() -> {}, fifthAck, "fifth");
        assertFalse(accepted, "5th task should be rejected (executor full)");

        blockLatch.countDown();
        Thread.sleep(200);

        CountDownLatch sixthDone = new CountDownLatch(1);
        assertTrue(executor.trySubmit(sixthDone::countDown, ack, "sixth"));
        assertTrue(sixthDone.await(5, TimeUnit.SECONDS));
    }

    @Test
    void trySubmit_shouldPauseContainersWhenFull() throws Exception {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        when(container.isContainerPaused()).thenReturn(false);
        executor.registerContainer(container);

        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch allStarted = new CountDownLatch(4);
        Acknowledgment ack = mock(Acknowledgment.class);

        for (int i = 0; i < 4; i++) {
            executor.trySubmit(() -> {
                allStarted.countDown();
                try { blockLatch.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, ack, "fill-" + i);
        }
        assertTrue(allStarted.await(5, TimeUnit.SECONDS));

        assertFalse(executor.trySubmit(() -> {}, ack, "overflow"));
        verify(container).pause();
        assertTrue(executor.isContainersPaused());

        when(container.isContainerPaused()).thenReturn(true);
        blockLatch.countDown();
        Thread.sleep(300);

        verify(container, atLeastOnce()).resume();
        assertFalse(executor.isContainersPaused());
    }

    @Test
    void trySubmit_shouldPreserveMdcContext() throws Exception {
        Acknowledgment ack = mock(Acknowledgment.class);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean mdcOk = new AtomicBoolean(false);

        MDC.put("testKey", "testValue");
        executor.trySubmit(() -> {
            mdcOk.set("testValue".equals(MDC.get("testKey")));
            latch.countDown();
        }, ack, "mdc-test");
        MDC.clear();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(mdcOk.get(), "MDC context should be propagated to worker thread");
    }
}
