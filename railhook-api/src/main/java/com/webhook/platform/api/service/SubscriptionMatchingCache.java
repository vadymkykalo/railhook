package com.webhook.platform.api.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.webhook.platform.api.domain.entity.Subscription;
import com.webhook.platform.api.domain.repository.SubscriptionRepository;
import com.webhook.platform.common.util.EventTypeMatcher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pre-compiled in-memory cache for subscription matching.
 * Eliminates the two SQL queries per event (exact + wildcard scan) from the hot ingestion path.
 *
 * <p>Cache entry per project holds:
 * <ul>
 *   <li>exactIndex — Map&lt;eventType, List&lt;Subscription&gt;&gt; for O(1) exact lookups</li>
 *   <li>wildcardSubs — pre-filtered list of wildcard subscriptions for O(W) matching</li>
 * </ul>
 *
 * <p>Invalidated by {@link SubscriptionService} on create/update/delete.
 * Short TTL (5 min) as safety net for missed invalidations.
 */
@Service
@Slf4j
public class SubscriptionMatchingCache {

    private final SubscriptionRepository subscriptionRepository;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    private final Cache<UUID, ProjectSubscriptions> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public SubscriptionMatchingCache(
            SubscriptionRepository subscriptionRepository,
            MeterRegistry meterRegistry) {
        this.subscriptionRepository = subscriptionRepository;
        this.cacheHitCounter = Counter.builder("subscription_cache_hits_total")
                .description("Subscription cache hits")
                .register(meterRegistry);
        this.cacheMissCounter = Counter.builder("subscription_cache_misses_total")
                .description("Subscription cache misses (DB load)")
                .register(meterRegistry);
    }

    /**
     * Returns all enabled subscriptions matching the given event type for the project.
     * On cache hit: zero DB queries, pure in-memory matching.
     */
    public List<Subscription> findMatching(UUID projectId, String eventType) {
        ProjectSubscriptions ps = cache.get(projectId, this::loadFromDb);

        List<Subscription> result = new ArrayList<>();

        // O(1) exact lookup
        List<Subscription> exact = ps.exactIndex().get(eventType);
        if (exact != null) {
            result.addAll(exact);
        }

        // O(W) wildcard matching (W = number of wildcard subs, typically small)
        for (Subscription wsub : ps.wildcardSubs()) {
            if (EventTypeMatcher.matches(wsub.getEventType(), eventType)) {
                result.add(wsub);
            }
        }

        return result;
    }

    /**
     * Evicts the cached subscriptions for a project.
     * Called by {@link SubscriptionService} on any subscription mutation.
     */
    public void evict(UUID projectId) {
        cache.invalidate(projectId);
        log.debug("Subscription cache evicted for project {}", projectId);
    }

    private ProjectSubscriptions loadFromDb(UUID projectId) {
        cacheMissCounter.increment();
        List<Subscription> allEnabled = subscriptionRepository.findByProjectIdAndEnabledTrue(projectId);

        Map<String, List<Subscription>> exactIndex = new HashMap<>();
        List<Subscription> wildcardSubs = new ArrayList<>();

        for (Subscription sub : allEnabled) {
            if (EventTypeMatcher.isWildcard(sub.getEventType())) {
                wildcardSubs.add(sub);
            } else {
                exactIndex.computeIfAbsent(sub.getEventType(), k -> new ArrayList<>()).add(sub);
            }
        }

        log.debug("Loaded {} subscriptions for project {} (exact patterns: {}, wildcard: {})",
                allEnabled.size(), projectId, exactIndex.size(), wildcardSubs.size());

        return new ProjectSubscriptions(exactIndex, wildcardSubs);
    }

    /**
     * Pre-partitioned subscription data for a project.
     */
    record ProjectSubscriptions(
            Map<String, List<Subscription>> exactIndex,
            List<Subscription> wildcardSubs
    ) {}
}
