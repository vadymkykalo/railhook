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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis cache of the month's event count; the database is the truth. A failed increment or one
 * answering 1 (the key was evicted under allkeys-lru) re-seeds that organization from the database.
 */
@Service
@Slf4j
public class QuotaCounterService {

    private static final String KEY_PREFIX = "quota:events:";

    private final RedissonClient redissonClient;
    private final EventRepository eventRepository;
    private final Counter fallbacks;

    private final Set<UUID> reseedNeeded = ConcurrentHashMap.newKeySet();

    public QuotaCounterService(RedissonClient redissonClient,
                               EventRepository eventRepository,
                               MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.eventRepository = eventRepository;
        this.fallbacks = Counter.builder("quota_counter_fallback_total")
                .description("Quota counter operations that Redis could not serve (counted from the database instead)")
                .register(meterRegistry);
    }

    /** Called after the event is persisted, so a failure here costs only cache accuracy. */
    public void increment() {
        UUID organizationId = TenantContext.require();
        try {
            RAtomicLong counter = redissonClient.getAtomicLong(currentKey());
            long val = counter.incrementAndGet();
            if (val == 1) {
                // Maybe evicted; the database count already includes this committed event.
                seed(counter);
            }
        } catch (Exception e) {
            fallbacks.increment();
            reseedNeeded.add(organizationId);
            log.warn("Redis quota increment failed for org={}; the counter is now short and the next "
                    + "quota check will re-seed it from the database: {}", organizationId, e.getMessage());
        }
    }

    public long getCurrentCount() {
        UUID organizationId = TenantContext.require();
        try {
            RAtomicLong counter = redissonClient.getAtomicLong(currentKey());

            if (reseedNeeded.remove(organizationId)) {
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
            return countFromDb();
        }
    }

    private long seed(RAtomicLong counter) {
        long dbCount = countFromDb();
        counter.set(dbCount);
        counter.expire(ttlForCurrentMonth());
        return dbCount;
    }

    private long countFromDb() {
        UUID organizationId = TenantContext.require();
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        Instant monthStart = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant monthEnd = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return eventRepository.countEventsAndIncomingEventsBetween(organizationId, monthStart, monthEnd);
    }

    private String currentKey() {
        UUID organizationId = TenantContext.require();
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        return KEY_PREFIX + organizationId + ":" + ym;
    }

    private Duration ttlForCurrentMonth() {
        // Expire at the end of next month so the key cannot vanish at a month boundary.
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        Instant expiry = ym.plusMonths(2).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        long seconds = Duration.between(Instant.now(), expiry).getSeconds();
        return Duration.ofSeconds(Math.max(seconds, 3600));
    }
}
