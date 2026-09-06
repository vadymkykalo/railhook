package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.domain.repository.EventRepository;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed event counter for fast quota checks.
 *
 * <p>Key: {@code quota:events:{orgId}:{YYYY-MM}} → atomic long, TTL = end of next month. On every
 * event ingest → {@link #increment()}. On quota check → {@link #getCurrentCount()}. The database
 * is the source of truth; Redis only saves a {@code COUNT(*)} per ingest.
 *
 * <p>Redis being unavailable is therefore a cache problem, not a correctness one — but only
 * because a lost write is treated as one. An increment that Redis refuses used to be logged at
 * DEBUG and forgotten: the counter stayed short by every event ingested during the outage, and
 * because the read path re-seeded from the database only when Redis held exactly {@code 0}, a
 * counter that came back short but non-zero was believed for the rest of the month. An
 * organization could pass a quota check it should have failed, and nothing said so. So:
 *
 * <ul>
 *   <li>every fall back to the database is counted on {@code quota_counter_fallback_total}, the
 *       same signal {@code RedisRateLimiterService} and {@code RedisConcurrencyControlService}
 *       publish for their own Redis outages;
 *   <li>an increment this instance could not apply arms a re-seed, and the next read replaces
 *       whatever Redis holds with the database count — which heals the shared key for every
 *       instance, not just this one;
 *   <li>the read path asks whether the key <em>exists</em> rather than whether it is above zero,
 *       so an organization that has genuinely sent nothing this month is answered from Redis
 *       instead of counting rows on every check.
 * </ul>
 */
@Service
@Slf4j
public class QuotaCounterService {

    private static final String KEY_PREFIX = "quota:events:";

    private final RedissonClient redissonClient;
    private final EventRepository eventRepository;
    private final Counter fallbacks;

    /**
     * Set when this instance loses an increment, cleared by the re-seed that repairs it. Process-
     * local on purpose: the instance that lost the write is the one that knows Redis is short, and
     * its re-seed writes the database count back into the shared key for everybody else.
     */
    private final AtomicBoolean reseedNeeded = new AtomicBoolean(false);

    public QuotaCounterService(RedissonClient redissonClient,
                               EventRepository eventRepository,
                               MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.eventRepository = eventRepository;
        this.fallbacks = Counter.builder("quota_counter_fallback_total")
                .description("Quota counter operations that Redis could not serve (counted from the database instead)")
                .register(meterRegistry);
    }

    /**
     * Increment the event counter for the current organization. Called after the event is
     * persisted, so a failure here never costs the caller their event — it costs the cache its
     * accuracy, which the next read repairs.
     */
    public void increment() {
        UUID organizationId = TenantContext.require();
        try {
            RAtomicLong counter = redissonClient.getAtomicLong(currentKey());
            long val = counter.incrementAndGet();
            // Set TTL on first increment (when counter transitions from 0→1)
            if (val == 1) {
                counter.expire(ttlForCurrentMonth());
            }
        } catch (Exception e) {
            fallbacks.increment();
            reseedNeeded.set(true);
            log.warn("Redis quota increment failed for org={}; the counter is now short and the next "
                    + "quota check will re-seed it from the database: {}", organizationId, e.getMessage());
        }
    }

    /**
     * The current organization's event count for this month, from Redis where Redis can be
     * trusted and from the database where it cannot.
     */
    public long getCurrentCount() {
        UUID organizationId = TenantContext.require();
        try {
            RAtomicLong counter = redissonClient.getAtomicLong(currentKey());

            if (reseedNeeded.compareAndSet(true, false)) {
                return seed(counter);
            }
            if (counter.isExists()) {
                return counter.get();
            }
            return seed(counter);
        } catch (Exception e) {
            fallbacks.increment();
            log.warn("Redis quota read failed for org={}, counting from the database instead: {}",
                    organizationId, e.getMessage());
            return countEventsFromDb();
        }
    }

    /** Replaces whatever the key holds with the database count, and hands that count back. */
    private long seed(RAtomicLong counter) {
        long dbCount = countEventsFromDb();
        counter.set(dbCount);
        counter.expire(ttlForCurrentMonth());
        return dbCount;
    }

    private long countEventsFromDb() {
        UUID organizationId = TenantContext.require();
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        Instant monthStart = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant monthEnd = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return eventRepository.countByOrganizationIdAndCreatedAtBetween(organizationId, monthStart, monthEnd);
    }

    private String currentKey() {
        UUID organizationId = TenantContext.require();
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        return KEY_PREFIX + organizationId + ":" + ym;
    }

    private Duration ttlForCurrentMonth() {
        // Expire at end of next month (buffer so we don't lose the key mid-month on edge)
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        Instant expiry = ym.plusMonths(2).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        long seconds = Duration.between(Instant.now(), expiry).getSeconds();
        return Duration.ofSeconds(Math.max(seconds, 3600)); // at least 1 hour
    }
}
