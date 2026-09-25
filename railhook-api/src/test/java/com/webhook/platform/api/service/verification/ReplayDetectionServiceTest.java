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

// A Redis failure once turned a verified webhook into a 500; the check now fails open.
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
    void aSignatureIsAReplayOnlyOnceItHasBeenSeen() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true, false);

        assertThat(service.isReplay("source-1", "sig")).isFalse();
        assertThat(service.isReplay("source-1", "sig")).isTrue();
    }

    @Test
    void aRedisErrorLetsTheVerifiedWebhookThroughAndCountsIt() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        assertThat(service.isReplay("source-1", "sig")).isFalse();
        assertThat(meterRegistry.get("incoming_replay_check_unavailable_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aRedisErrorWhileReleasingAMarkerDoesNotReplaceTheErrorBeingHandled() {
        when(redisTemplate.delete(anyString()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        assertThatCode(() -> service.unmark("source-1", "sig")).doesNotThrowAnyException();
    }
}
