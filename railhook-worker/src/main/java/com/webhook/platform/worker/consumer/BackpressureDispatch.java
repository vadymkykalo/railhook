package com.webhook.platform.worker.consumer;

import com.webhook.platform.worker.service.BoundedAsyncExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.Acknowledgment;

/**
 * The one decision both consumers have to get right when the bounded executor is full.
 *
 * <p>Constructed per consumer, because the two directions run on separate executors with their
 * own pools and pause behaviour.
 *
 * <p>A full executor must still ack. Both listener factories set {@code asyncAcks(true)}, under
 * which an unacked record blocks this partition's offset commits until a rebalance. Kafka's job
 * is done either way — the retry ladder, not redelivery, drives reprocessing — so the obligation
 * is handed back to the ladder and the record is acked. Getting this wrong stalls a partition
 * until a restart.
 */
@Slf4j
public class BackpressureDispatch {

    private final BoundedAsyncExecutor asyncExecutor;

    public BackpressureDispatch(BoundedAsyncExecutor asyncExecutor) {
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * @param handBack must stamp the row's next-retry time: both schedulers ignore rows without
     *                 one, so a hand-back that skips it strands the obligation
     */
    public void dispatch(Runnable task, Acknowledgment ack, String id, Runnable handBack) {
        if (asyncExecutor.trySubmit(task, ack, id)) {
            return;
        }
        log.debug("Executor full, handing {} back to the retry ladder and acking", id);
        handBack.run();
        ack.acknowledge();
    }
}
