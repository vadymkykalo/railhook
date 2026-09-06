package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Redis-backed tunnel bandwidth metering.
 * <p>
 * Key: {@code tunnel:bw:{orgId}:{YYYY-MM}} → atomic long (bytes), TTL = end of next month.
 * <p>
 * Incremented on every tunnel request with request + response body sizes.
 * Fire-and-forget — never blocks the response path.
 */
@Service
@Slf4j
public class TunnelBandwidthService {

    private static final String KEY_PREFIX = "tunnel:bw:";

    private final RedissonClient redissonClient;
    private final Counter bytesCounter;

    public TunnelBandwidthService(RedissonClient redissonClient, MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.bytesCounter = Counter.builder("tunnel_bandwidth_bytes_total")
                .description("Total bytes transferred through tunnels")
                .register(meterRegistry);
    }

    /**
     * Record bytes transferred through a tunnel for an organization.
     * Fire-and-forget — if Redis is down, just record the Prometheus metric.
     */
    public void recordBytes(long bytes) {
        UUID organizationId = TenantContext.require();
        if (bytes <= 0) return;
        bytesCounter.increment(bytes);
        try {
            String key = currentKey();
            RAtomicLong counter = redissonClient.getAtomicLong(key);
            long val = counter.addAndGet(bytes);
            if (val == bytes) {
                counter.expire(ttlForCurrentMonth());
            }
        } catch (Exception e) {
            log.debug("Redis tunnel bandwidth increment failed for org={}: {}", organizationId, e.getMessage());
        }
    }

    /**
     * Get current month's bandwidth usage for an organization (bytes).
     */
    public long getCurrentUsage() {
        UUID organizationId = TenantContext.require();
        try {
            String key = currentKey();
            return redissonClient.getAtomicLong(key).get();
        } catch (Exception e) {
            log.debug("Redis tunnel bandwidth read failed for org={}: {}", organizationId, e.getMessage());
            return 0;
        }
    }

    private String currentKey() {
        UUID organizationId = TenantContext.require();
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        return KEY_PREFIX + organizationId + ":" + ym;
    }

    private Duration ttlForCurrentMonth() {
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        Instant expiry = ym.plusMonths(2).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        long seconds = Duration.between(Instant.now(), expiry).getSeconds();
        return Duration.ofSeconds(Math.max(seconds, 3600));
    }
}
