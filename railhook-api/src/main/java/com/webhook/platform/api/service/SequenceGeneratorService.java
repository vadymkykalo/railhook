package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.repository.DeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** A lost Redis key is reseeded from the durable high-water mark, or ordering breaks. */
@Service
@Slf4j
public class SequenceGeneratorService {

    private static final String SEQUENCE_KEY_PREFIX = "seq:endpoint:";

    private final RedissonClient redissonClient;
    private final DeliveryRepository deliveryRepository;
    private final MeterRegistry meterRegistry;

    public SequenceGeneratorService(
            RedissonClient redissonClient,
            DeliveryRepository deliveryRepository,
            MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.deliveryRepository = deliveryRepository;
        this.meterRegistry = meterRegistry;
    }

    // Only after the delivery commits: a rollback inside the ingest burned a number.
    public long nextSequence(UUID endpointId) {
        String key = SEQUENCE_KEY_PREFIX + endpointId;
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        reseedFromDurableHighWaterMarkIfMissing(counter, endpointId);
        long seq = counter.incrementAndGet();
        log.debug("Generated sequence {} for endpoint {}", seq, endpointId);
        return seq;
    }

    // compareAndSet(0, seed) cannot overwrite a value a concurrent caller already set.
    private void reseedFromDurableHighWaterMarkIfMissing(RAtomicLong counter, UUID endpointId) {
        if (counter.isExists()) {
            return;
        }
        long seed = durableHighWaterMark(endpointId);
        if (seed <= 0) {
            return; // incrementAndGet() starting at 1 is already correct
        }
        boolean seeded = counter.compareAndSet(0, seed);
        if (seeded) {
            log.warn("Sequence counter cache miss for endpoint {} — reseeded from durable high-water mark {}",
                    endpointId, seed);
            meterRegistry.counter("webhook_sequence_reseeded_total").increment();
        }
    }

    private long durableHighWaterMark(UUID endpointId) {
        Long max = deliveryRepository.findMaxSequenceNumber(endpointId);
        return max == null ? 0L : max;
    }

    public long currentSequence(UUID endpointId) {
        String key = SEQUENCE_KEY_PREFIX + endpointId;
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        return counter.get();
    }

    /** Never moves the counter backwards. Used by the reconciliation job. */
    public void reseedIfBehind(UUID endpointId, long minimum) {
        String key = SEQUENCE_KEY_PREFIX + endpointId;
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        long current;
        int attempts = 0;
        do {
            current = counter.get();
            if (current >= minimum) {
                return;
            }
            attempts++;
        } while (!counter.compareAndSet(current, minimum) && attempts < 10);
    }

    public void resetSequence(UUID endpointId) {
        String key = SEQUENCE_KEY_PREFIX + endpointId;
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        counter.set(0);
        log.info("Reset sequence counter for endpoint {}", endpointId);
    }
}
