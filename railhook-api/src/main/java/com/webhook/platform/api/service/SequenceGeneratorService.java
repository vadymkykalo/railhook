package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.repository.DeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Generates monotonically increasing sequence numbers per endpoint.
 * Used for FIFO ordering guarantees in webhook delivery.
 *
 * <p>The counter lives in Redis, which is not durable: a flush or an eviction silently resets it,
 * and new events then start again at 1 while the worker's cursor already holds a much higher
 * value — at which point ordering is dead for that endpoint. {@link #nextSequence} reseeds from
 * the durable high-water mark the moment it notices the key is gone.
 */
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

    /**
     * The next sequence number, from 1. Generate it after the delivery it will be stamped onto has
     * committed, never inside the ingest transaction: a rollback there burned a number no delivery
     * would ever carry.
     */
    public long nextSequence(UUID endpointId) {
        String key = SEQUENCE_KEY_PREFIX + endpointId;
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        reseedFromDurableHighWaterMarkIfMissing(counter, endpointId);
        long seq = counter.incrementAndGet();
        log.debug("Generated sequence {} for endpoint {}", seq, endpointId);
        return seq;
    }

    /**
     * Reseeds from the durable high-water mark only while the key is still absent. A missing key
     * reads as 0, so {@code compareAndSet(0, seed)} is the "only if still absent" primitive and a
     * concurrent reseed cannot stomp on a value somebody else already established.
     */
    private void reseedFromDurableHighWaterMarkIfMissing(RAtomicLong counter, UUID endpointId) {
        if (counter.isExists()) {
            return;
        }
        long seed = durableHighWaterMark(endpointId);
        if (seed <= 0) {
            return; // nothing to reseed from; incrementAndGet() starting at 1 is already correct
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

    /**
     * Bumps the counter to at least {@code minimum} if it is behind, never backwards. The
     * reconciliation job uses this to heal a desync it detects against the high-water mark.
     */
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

    /**
     * Resets the sequence counter for an endpoint.
     * Use with caution - typically only for testing or endpoint recreation.
     *
     * @param endpointId the endpoint ID
     */
    public void resetSequence(UUID endpointId) {
        String key = SEQUENCE_KEY_PREFIX + endpointId;
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        counter.set(0);
        log.info("Reset sequence counter for endpoint {}", endpointId);
    }
}
