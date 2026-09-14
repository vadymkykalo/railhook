package com.webhook.platform.api.service.verification;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a replay check does when Redis cannot answer it.
 *
 * <p>It used to throw, and ingress answered a verified webhook 500 — lost for good from a provider
 * that does not retry. The check now fails open: the request reaching it has already proved its
 * signature, so the worst it can let through is a genuine webhook sent twice.
 */
class ReplayDetectionServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private SimpleMeterRegistry meterRegistry;
    private ReplayDetectionService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        meterRegistry = new SimpleMeterRegistry();
        service = new ReplayDetectionService(redisTemplate, 5, meterRegistry);
    }

    @Test
    void aSignatureSeenForTheFirstTimeIsNotAReplay() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        assertThat(service.isReplay("source-1", "sig")).isFalse();
    }

    @Test
    void aSignatureAlreadySeenIsAReplay() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        assertThat(service.isReplay("source-1", "sig")).isTrue();
    }

    @Test
    void aRedisErrorLetsTheVerifiedWebhookThroughAndCountsIt() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        assertThat(service.isReplay("source-1", "sig")).isFalse();
        assertThat(meterRegistry.get("incoming_replay_check_unavailable_total").counter().count())
                .as("replay protection was off for this request; an operator has to be able to see that")
                .isEqualTo(1.0);
    }

    @Test
    void aRedisErrorWhileReleasingAMarkerDoesNotReplaceTheErrorBeingHandled() {
        when(redisTemplate.delete(anyString()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        assertThatCode(() -> service.unmark("source-1", "sig")).doesNotThrowAnyException();
    }
}
