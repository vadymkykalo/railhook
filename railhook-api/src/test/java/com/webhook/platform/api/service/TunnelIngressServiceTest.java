package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.repository.TunnelRequestLogRepository;
import com.webhook.platform.api.security.SuspensionCheck;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;

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
            new SimpleMeterRegistry(), Runnable::run, mock(SuspensionCheck.class));

    // A deleted tunnel no longer counts toward the plan limit, so its lingering socket must not carry traffic.
    @Test
    void aTunnelWhoseSessionIsNoLongerActiveIsRefusedEvenWhileItsSocketIsUp() {
        when(coordinator.isActiveInCluster("tun-deleted")).thenReturn(true);
        when(rateLimiter.tryAcquireForSlug(anyString(), anyInt())).thenReturn(true);
        when(tunnelService.getActiveBySlug("tun-deleted"))
                .thenThrow(new ResponseStatusException(HttpStatus.GONE, "Tunnel is no longer active"));

        TunnelIngressService.Outcome outcome = ingress.forward("tun-deleted",
                TunnelRequestMessage.builder().requestId("r1").method("POST").path("/").build(), "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(outcome).isInstanceOf(TunnelIngressService.Outcome.Refused.class);
        assertThat(((TunnelIngressService.Outcome.Refused) outcome).error()).isEqualTo("tunnel_offline");
        verify(coordinator, never()).forwardRequest(anyString(), any());
    }
}
