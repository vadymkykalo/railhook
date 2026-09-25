package com.webhook.platform.api.service.verification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

@Service
@Slf4j
public class ReplayDetectionService {

    private static final String REDIS_KEY_PREFIX = "webhook:replay:";
    // For a body-only HMAC with no timestamp, this is the only bound on replay.
    private final Duration replayCacheTtl;

    private final StringRedisTemplate redisTemplate;

    private final Counter checkUnavailable;

    public ReplayDetectionService(
            StringRedisTemplate redisTemplate,
            @Value("${webhook.ingress.replay-window-minutes:5}") int replayWindowMinutes,
            MeterRegistry meterRegistry) {
        this.replayCacheTtl = Duration.ofMinutes(replayWindowMinutes);
        this.redisTemplate = redisTemplate;
        this.checkUnavailable = Counter.builder("incoming_replay_check_unavailable_total")
                .description("Verified incoming webhooks accepted without a replay check because Redis did not answer")
                .register(meterRegistry);
    }

    // Fails open: the signature already verified, and failing closed lost webhooks from
    // providers that do not retry.
    public boolean isReplay(String sourceId, String signature) {
        String signatureHash = hashSignature(signature);
        String redisKey = REDIS_KEY_PREFIX + sourceId + ":" + signatureHash;

        Boolean wasSet;
        try {
            wasSet = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", replayCacheTtl);
        } catch (RuntimeException e) {
            checkUnavailable.increment();
            log.error("Replay check unavailable, accepting the verified webhook without one: sourceId={}, error={}",
                    sourceId, e.getMessage());
            return false;
        }

        if (Boolean.FALSE.equals(wasSet)) {
            log.warn("Replay attack detected: sourceId={}, signatureHash={}", sourceId, signatureHash);
            return true;
        }

        return false;
    }

    // Only when the write never committed, or the provider's re-send is rejected as a replay.
    public void unmark(String sourceId, String signature) {
        String signatureHash = hashSignature(signature);
        String redisKey = REDIS_KEY_PREFIX + sourceId + ":" + signatureHash;
        try {
            redisTemplate.delete(redisKey);
        } catch (RuntimeException e) {
            // A failed persist is already on its way out; throwing would replace its error.
            log.error("Could not release replay marker: sourceId={}, error={}", sourceId, e.getMessage());
        }
    }

    private static String hashSignature(String signature) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signature.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash signature for replay detection", e);
        }
    }
}
