package com.webhook.platform.worker.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.webhook.platform.worker.domain.entity.Transformation;
import com.webhook.platform.worker.domain.repository.TransformationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class TransformationCacheService {

    private final TransformationRepository transformationRepository;

    private final Cache<UUID, Optional<Transformation>> cache;

    public TransformationCacheService(TransformationRepository transformationRepository) {
        this.transformationRepository = transformationRepository;
        this.cache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofSeconds(30))
                .recordStats()
                .build();
    }

    /** Local cache, 30s TTL. */
    public Optional<Transformation> findById(UUID id) {
        return cache.get(id, key -> {
            log.debug("Cache miss for transformation {}, loading from DB", key);
            return transformationRepository.findById(key);
        });
    }

    /** Null when the transformation is missing or disabled. */
    public String findEnabledTemplate(UUID id) {
        return findById(id)
                .filter(Transformation::getEnabled)
                .map(Transformation::getTemplate)
                .orElse(null);
    }

    public void evict(UUID id) {
        cache.invalidate(id);
    }

    public void evictAll() {
        cache.invalidateAll();
    }
}
