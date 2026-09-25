package com.webhook.platform.worker.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.webhook.platform.common.transform.TransformationKind;
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

    public Optional<Transformation> findById(UUID id) {
        return cache.get(id, key -> {
            log.debug("Cache miss for transformation {}, loading from DB", key);
            return transformationRepository.findById(key);
        });
    }

    /**
     * The same TEXT column holds a JSON template or a script, and which one cannot be told by
     * looking, so the kind always travels with the source. A blank source means no transformation.
     */
    public record Resolved(TransformationKind kind, String source) {

        public static Resolved template(String source) {
            return new Resolved(TransformationKind.TEMPLATE, source);
        }

        public boolean isConfigured() {
            return source != null && !source.isBlank();
        }
    }

    public Resolved findEnabled(UUID id) {
        return findById(id)
                .filter(Transformation::getEnabled)
                .map(t -> new Resolved(
                        t.getKind() == null ? TransformationKind.TEMPLATE : t.getKind(),
                        t.getTemplate()))
                .orElse(null);
    }

    public String findEnabledTemplate(UUID id) {
        Resolved resolved = findEnabled(id);
        return resolved == null ? null : resolved.source();
    }

    public void evict(UUID id) {
        cache.invalidate(id);
    }

    public void evictAll() {
        cache.invalidateAll();
    }
}
