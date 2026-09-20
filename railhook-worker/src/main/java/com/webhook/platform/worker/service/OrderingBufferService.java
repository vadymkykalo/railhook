package com.webhook.platform.worker.service;

import com.webhook.platform.worker.domain.entity.OrderingCursor;
import com.webhook.platform.worker.domain.repository.OrderingCursorRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.LongCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages ordering buffer for FIFO delivery guarantees.
 * Tracks last delivered sequence per endpoint and buffers out-of-order deliveries.
 * Uses Redis as cache (24h TTL) with Postgres as durable fallback.
 *
 * <p>Redis is never allowed to stop an ordered endpoint. Its reads fall back to the Postgres
 * cursor and its writes are best effort: an exception escaping here surfaced in the claim, after
 * the Delivery was already PROCESSING, and left it to the stuck sweep until the hard cap.
 */
@Service
@Slf4j
public class OrderingBufferService {

    private static final String DELIVERED_SEQ_KEY_PREFIX = "seq:delivered:";
    private static final String BUFFER_KEY_PREFIX = "seq:buffer:";
    private static final String BUFFER_KEY_PATTERN = BUFFER_KEY_PREFIX + "*";

    private final RedissonClient redissonClient;
    private final OrderingCursorRepository cursorRepository;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;
    private final Duration gapTimeout;
    private final Duration deliveredSeqTtl;
    private final Duration bufferTtl;
    private final String cursorCasScript;
    // Registered once, not per call: Micrometer keeps only the first meter for an untagged
    // name, so re-registering made every later endpoint's gauge a no-op. Resynced from Redis
    // rather than counted, because entries also vanish via TTL and the gap-timeout path.
    private final AtomicLong totalBufferedDeliveries = new AtomicLong(0);

    public OrderingBufferService(
            RedissonClient redissonClient,
            OrderingCursorRepository cursorRepository,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry,
            @Value("${ordering.gap-timeout-seconds:60}") int gapTimeoutSeconds,
            @Value("${ordering.delivered-seq-ttl-hours:24}") int deliveredSeqTtlHours,
            @Value("${ordering.buffer-ttl-minutes:10}") int bufferTtlMinutes) {
        this.redissonClient = redissonClient;
        this.cursorRepository = cursorRepository;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
        this.gapTimeout = Duration.ofSeconds(gapTimeoutSeconds);
        this.deliveredSeqTtl = Duration.ofHours(deliveredSeqTtlHours);
        this.bufferTtl = Duration.ofMinutes(bufferTtlMinutes);
        this.cursorCasScript = loadLuaScript("lua/ordering_cursor_cas.lua");

        Gauge.builder("webhook_ordering_buffer_size", totalBufferedDeliveries, AtomicLong::get)
                .description("Total deliveries currently buffered awaiting their predecessor sequence, summed across all endpoints")
                .register(meterRegistry);
    }

    /**
     * Recomputes the gauge by summing every per-endpoint buffer key. Aggregated rather than
     * tagged: endpoint UUIDs are unbounded cardinality. {@code getKeysByPattern} is SCAN-based.
     */
    @Scheduled(fixedDelayString = "${ordering.buffer-gauge-resync-ms:30000}")
    public void resyncBufferSizeGauge() {
        try {
            long total = 0;
            for (String key : redissonClient.getKeys().getKeysByPattern(BUFFER_KEY_PATTERN)) {
                total += redissonClient.getScoredSortedSet(key).size();
            }
            totalBufferedDeliveries.set(total);
        } catch (Exception e) {
            log.warn("Failed to resync webhook_ordering_buffer_size gauge: {}", e.getMessage());
        }
    }

    private String loadLuaScript(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Lua script: " + path, e);
        }
    }

    /** True when this is the next expected sequence; false means it should be buffered. */
    public boolean canDeliver(UUID endpointId, long sequenceNumber) {
        Long lastDelivered = getLastDeliveredSequence(endpointId);
        
        if (lastDelivered == null) {
            return sequenceNumber == 1;
        }
        
        return sequenceNumber == lastDelivered + 1;
    }

    /**
     * Redis first, Postgres when the TTL has lapsed or Redis is unreachable. Null when nothing has
     * been delivered.
     */
    public Long getLastDeliveredSequence(UUID endpointId) {
        String key = DELIVERED_SEQ_KEY_PREFIX + endpointId;
        RBucket<Long> bucket = null;
        try {
            // LongCodec: the CAS script in markDelivered() writes a plain decimal string, and
            // Redisson's default Kryo codec cannot read it back. Every accessor must agree.
            bucket = redissonClient.getBucket(key, LongCodec.INSTANCE);
            Long fromRedis = bucket.get();
            if (fromRedis != null) {
                return fromRedis;
            }
        } catch (Exception e) {
            log.warn("Redis unavailable reading ordering cursor for endpoint {}, reading Postgres: {}",
                    endpointId, e.getMessage());
            bucket = null;
        }

        Long fromDb;
        try {
            fromDb = cursorRepository.findById(endpointId)
                    .map(OrderingCursor::getLastDeliveredSequence)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Failed to query ordering cursor from DB for endpoint {}: {}",
                    endpointId, e.getMessage());
            return null;
        }
        if (fromDb == null) {
            return null;
        }

        meterRegistry.counter("webhook_ordering_cache_miss_total").increment();
        if (bucket != null) {
            try {
                bucket.set(fromDb, deliveredSeqTtl);
                log.debug("Warmed Redis cache from DB for endpoint {}: seq={}", endpointId, fromDb);
            } catch (Exception e) {
                log.warn("Failed to warm Redis ordering cursor for endpoint {}: {}", endpointId, e.getMessage());
            }
        }
        return fromDb;
    }

    /**
     * Advances the cursor in Postgres, then in Redis.
     *
     * <p>Postgres is authoritative and applies {@code GREATEST}, so it cannot regress. Redis is
     * then advanced from that value — never from the argument — by a CAS script that only moves
     * the key upward, so the cache can converge towards Postgres but never fall behind it.
     *
     * <p>The Postgres write commits before Redis is touched. Both used to share one transaction,
     * so a Redis error rolled the authoritative cursor back with it.
     */
    public void markDelivered(UUID endpointId, long sequenceNumber) {
        long authoritative;
        boolean postgresWriteFailed = false;
        try {
            authoritative = transactionTemplate.execute(tx -> cursorRepository.upsertCursor(endpointId, sequenceNumber));
        } catch (Exception e) {
            log.error("Failed to persist ordering cursor to DB for endpoint {}, seq={}: {}",
                    endpointId, sequenceNumber, e.getMessage());
            // Best effort so the endpoint does not stall; this window is where a regression
            // could occur, hence the loud log above.
            authoritative = sequenceNumber;
            postgresWriteFailed = true;
        }

        String key = DELIVERED_SEQ_KEY_PREFIX + endpointId;
        try {
            RScript script = redissonClient.getScript(LongCodec.INSTANCE);
            Long advanced = script.eval(
                    RScript.Mode.READ_WRITE,
                    cursorCasScript,
                    RScript.ReturnType.LONG,
                    Collections.singletonList(key),
                    authoritative,
                    deliveredSeqTtl.toMillis());

            if (advanced != null && advanced == 1L) {
                log.debug("Marked sequence {} as delivered for endpoint {} (authoritative={})",
                        sequenceNumber, endpointId, authoritative);
                meterRegistry.counter("webhook_ordering_sequence_advanced").increment();
            }
        } catch (Exception e) {
            // A key left behind is only ever below Postgres, and the next markDelivered moves it up.
            log.warn("Failed to advance Redis ordering cursor for endpoint {} to {}: {}",
                    endpointId, authoritative, e.getMessage());
        }
        if (postgresWriteFailed) {
            meterRegistry.counter("webhook_ordering_cursor_db_write_failed_total").increment();
        }
    }

    /**
     * Adds a delivery to the waiting buffer, released when its predecessor is delivered.
     */
    public void bufferDelivery(UUID endpointId, UUID deliveryId, long sequenceNumber) {
        String key = BUFFER_KEY_PREFIX + endpointId;
        int bufferSize;
        try {
            RScoredSortedSet<String> buffer = redissonClient.getScoredSortedSet(key);
            buffer.add(sequenceNumber, deliveryId.toString());
            buffer.expire(bufferTtl);
            bufferSize = buffer.size();
        } catch (Exception e) {
            // The row is parked with a retry time either way; the entry only lets its
            // predecessor's release send it sooner.
            log.warn("Redis unavailable buffering delivery {} (seq={}) for endpoint {}: {}",
                    deliveryId, sequenceNumber, endpointId, e.getMessage());
            return;
        }

        log.debug("Buffered delivery {} (seq={}) for endpoint {}, buffer size: {}", deliveryId, sequenceNumber, endpointId, bufferSize);
        if (bufferSize > 100) {
            log.warn("Ordering buffer growing large for endpoint {}: {} deliveries buffered", endpointId, bufferSize);
        }
        meterRegistry.counter("webhook_ordering_buffered_total").increment();
    }

    /**
     * Deliveries whose turn has come: sequential numbers starting from the next expected one.
     *
     * <p>Also drops every entry the cursor has already passed. Only the entry at the next expected
     * sequence used to leave, so a Delivery that parked and was then sent by the scheduler rather
     * than by this trigger stayed in forever; each park refreshes the whole key's TTL, so a busy
     * ordered endpoint's buffer never expired and only grew.
     */
    public List<UUID> getReadyDeliveries(UUID endpointId) {
        Long lastDelivered = getLastDeliveredSequence(endpointId);
        long nextExpected = (lastDelivered == null) ? 1 : lastDelivered + 1;
        
        String key = BUFFER_KEY_PREFIX + endpointId;
        List<UUID> ready = new ArrayList<>();
        try {
            RScoredSortedSet<String> buffer = redissonClient.getScoredSortedSet(key);
            buffer.removeRangeByScore(Double.NEGATIVE_INFINITY, true, nextExpected, false);

            Collection<String> entries = buffer.valueRange(nextExpected, true, nextExpected, true);
            for (String deliveryIdStr : entries) {
                ready.add(UUID.fromString(deliveryIdStr));
                buffer.remove(deliveryIdStr);
            }
        } catch (Exception e) {
            // Whatever is parked is re-polled by the scheduler at its own retry time.
            log.warn("Redis unavailable releasing buffered deliveries for endpoint {}: {}",
                    endpointId, e.getMessage());
        }
        
        if (!ready.isEmpty()) {
            log.info("Released {} buffered deliveries for endpoint {} at seq {}", 
                    ready.size(), endpointId, nextExpected);
        }
        
        return ready;
    }

    /**
     * How long a Delivery waits on a gap before it may be let through out of order. Declared
     * once, here: the gate measures the blocking Delivery's next Attempt against the same window.
     */
    public Duration gapTimeout() {
        return gapTimeout;
    }

    /**
     * Has a delivery blocked on a missing predecessor waited longer than the gap timeout?
     *
     * <p>Measured from when it was first buffered, not from ingest: measuring from ingest made
     * any backlog older than the timeout unconditionally true, silently turning ordering off
     * during exactly the fan-out bursts it exists for. Callers, not this, count the metric.
     */
    public boolean isGapTimedOut(Instant firstBufferedAt) {
        if (firstBufferedAt == null) {
            return false; // Never buffered before -- we haven't started waiting yet.
        }
        return Duration.between(firstBufferedAt, Instant.now()).compareTo(gapTimeout) > 0;
    }

    public void removeFromBuffer(UUID endpointId, UUID deliveryId) {
        String key = BUFFER_KEY_PREFIX + endpointId;
        try {
            redissonClient.getScoredSortedSet(key).remove(deliveryId.toString());
        } catch (Exception e) {
            // Must not stop the caller moving the cursor; the entry is pruned once the cursor passes it.
            log.warn("Redis unavailable removing delivery {} from buffer for endpoint {}: {}",
                    deliveryId, endpointId, e.getMessage());
        }
    }

    public int getBufferSize(UUID endpointId) {
        String key = BUFFER_KEY_PREFIX + endpointId;
        RScoredSortedSet<String> buffer = redissonClient.getScoredSortedSet(key);
        return buffer.size();
    }
}
