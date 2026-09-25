package com.webhook.platform.worker.consumer;

import com.webhook.platform.worker.service.BoundedAsyncExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.Acknowledgment;

/**
 * A full executor must still ack. Under asyncAcks an unacked record blocks the partition's commits
 * until a rebalance, and the retry ladder, not Kafka redelivery, drives reprocessing. So the
 * obligation goes back to the ladder and the record is acked.
 */
@Slf4j
public class BackpressureDispatch {

    private final BoundedAsyncExecutor asyncExecutor;

    public BackpressureDispatch(BoundedAsyncExecutor asyncExecutor) {
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * {@code handBack} must stamp the row's next retry time: the schedulers ignore rows without
     * one, so skipping it strands the obligation.
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
