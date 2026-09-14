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

/**
 * Redis-backed replay detection for incoming webhook signatures.
 * Caches seen signatures with 5-minute TTL to prevent replay attacks.
 */
@Service
@Slf4j
public class ReplayDetectionService {

    private static final String REDIS_KEY_PREFIX = "webhook:replay:";
    /**
     * How long a signature is remembered as already-seen.
     *
     * <p>For a provider whose scheme carries a timestamp this is a duplicate-suppression
     * window and five minutes is generous. For the raw-hex generic HMAC it is the <em>only</em>
     * bound on replay — that shape signs the body alone, so a captured request stays
     * verifiable for as long as the secret lives and nothing but this cache stops it being
     * sent again. Raise it for a source like that, at the cost of Redis holding one key per
     * signature for the window.</p>
     */
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

    /**
     * Check if this signature has been seen recently (replay attack).
     * If not seen, mark it as seen for the TTL window.
     *
     * <p>Fails open: when Redis does not answer, the answer is "not a replay". Deliberately, and
     * unlike the ingress rate limit, which fails closed. That one stands between an
     * unauthenticated URL and the database; this one only ever sees a request whose signature
     * has already verified, so what an outage can let through is a genuine webhook delivered
     * twice — and a provider that sends an event id is still deduplicated on it. Failing closed
     * instead turned the Redis error into a 500, and a provider that does not retry (GitHub,
     * GitLab) lost the webhook for good. The counter is how an operator sees the window.
     *
     * @param sourceId unique identifier of the incoming source
     * @param signature the webhook signature to check
     * @return true if this is a replay (already seen), false if first time or if Redis could not
     *         be asked
     */
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

    /**
     * Undo a previous {@link #isReplay} mark. isReplay marks a signature as seen the
     * moment it is first checked, before the caller has actually persisted anything for it. If
     * the write that was supposed to follow never commits (a validation failure downstream, an
     * unresolvable duplicate-key race, ...), the mark must not survive -- otherwise the
     * provider's legitimate re-send of the exact same webhook is rejected as a replay attack
     * for the rest of the TTL window and the event is lost for good instead of merely delayed.
     * Only call this for a signature this same request actually marked; never call it after a
     * successful commit.
     */
    public void unmark(String sourceId, String signature) {
        String signatureHash = hashSignature(signature);
        String redisKey = REDIS_KEY_PREFIX + sourceId + ":" + signatureHash;
        try {
            redisTemplate.delete(redisKey);
        } catch (RuntimeException e) {
            // Called while a failed persist is already on its way out. Throwing here would replace
            // that error with a Redis one; the mark simply outlives its TTL instead.
            log.error("Could not release replay marker: sourceId={}, error={}", sourceId, e.getMessage());
        }
    }

    /**
     * Hash the signature to reduce Redis key size and prevent leaking raw signatures in logs.
     */
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
