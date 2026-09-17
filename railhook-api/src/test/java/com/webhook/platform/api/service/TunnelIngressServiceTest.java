package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.repository.TunnelRequestLogRepository;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TunnelIngressServiceTest {

    private final TunnelService tunnelService = mock(TunnelService.class);
    private final RedisTunnelCoordinator coordinator = mock(RedisTunnelCoordinator.class);
    private final RedisRateLimiterService rateLimiter = mock(RedisRateLimiterService.class);
    private final TunnelIngressService ingress = new TunnelIngressService(tunnelService, coordinator, rateLimiter,
            mock(TunnelRequestLogRepository.class), mock(TunnelBandwidthService.class),
            new SimpleMeterRegistry(), Runnable::run);

    // A deleted tunnel whose socket has not gone yet — or whose close never reached the owning
    // instance — must not keep carrying traffic: it no longer counts towards the plan's tunnel
    // limit, and its bandwidth can no longer be metered.
    @Test
    void aTunnelWhoseSessionIsNoLongerActiveIsRefusedEvenWhileItsSocketIsUp() {
        when(coordinator.isActiveInCluster("tun-deleted")).thenReturn(true);
        when(rateLimiter.tryAcquireForSlug(anyString(), anyInt())).thenReturn(true);
        when(tunnelService.isForwardable("tun-deleted")).thenReturn(false);

        TunnelIngressService.Outcome outcome = ingress.forward("tun-deleted",
                TunnelRequestMessage.builder().requestId("r1").method("POST").path("/").build(), "{}");

        assertThat(outcome).isInstanceOf(TunnelIngressService.Outcome.Refused.class);
        assertThat(((TunnelIngressService.Outcome.Refused) outcome).error()).isEqualTo("tunnel_offline");
        verify(coordinator, never()).forwardRequest(anyString(), any());
    }
}
