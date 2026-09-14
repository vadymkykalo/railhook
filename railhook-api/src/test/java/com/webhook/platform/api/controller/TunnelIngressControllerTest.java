package com.webhook.platform.api.controller;

import com.webhook.platform.api.service.TunnelIngressService;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A tunnel URL is a base, not an endpoint: a developer points Stripe at
 * {@code /tunnel/<slug>/webhooks/stripe} and expects {@code /webhooks/stripe} on localhost.
 * Only the bare slug was mapped, so every path below it answered the API's own 404 and never
 * reached the CLI — found on production, where deliveries to a tunnel sub-path failed with 404.
 */
class TunnelIngressControllerTest {

    private TunnelIngressService tunnelIngressService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tunnelIngressService = mock(TunnelIngressService.class);
        when(tunnelIngressService.forward(anyString(), any(), any()))
                .thenReturn(new TunnelIngressService.Outcome.Refused("rate_limit_exceeded", "stop here"));
        mockMvc = MockMvcBuilders.standaloneSetup(new TunnelIngressController(tunnelIngressService)).build();
    }

    @Test
    void forwardsAPathBelowTheSlugToTheTunnel() throws Exception {
        mockMvc.perform(post("/tunnel/tun-abc123/webhooks/stripe?attempt=2")
                        .contentType("application/json").content("{\"id\":\"evt_1\"}"))
                .andExpect(status().isTooManyRequests());

        ArgumentCaptor<TunnelRequestMessage> sent = ArgumentCaptor.forClass(TunnelRequestMessage.class);
        verify(tunnelIngressService).forward(eq("tun-abc123"), sent.capture(), eq("{\"id\":\"evt_1\"}"));
        assertEquals("/webhooks/stripe", sent.getValue().getPath());
        assertEquals("attempt=2", sent.getValue().getQueryString());
    }

    /**
     * A tunnel whose CLI is not connected is temporarily unavailable, not a broken upstream. It
     * answered 502, which a CDN replaces with its own "Bad gateway" page — on production the
     * dead tunnel looked like the whole site was down — and which providers do not all retry.
     */
    @Test
    void anOfflineTunnelAnswers503() throws Exception {
        when(tunnelIngressService.forward(anyString(), any(), any()))
                .thenReturn(new TunnelIngressService.Outcome.Refused("tunnel_offline", "Tunnel is not connected"));

        mockMvc.perform(post("/tunnel/tun-abc123/webhooks/stripe").contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void stillForwardsTheBareSlug() throws Exception {
        mockMvc.perform(post("/tunnel/tun-abc123").contentType("application/json").content("{}"))
                .andExpect(status().isTooManyRequests());

        ArgumentCaptor<TunnelRequestMessage> sent = ArgumentCaptor.forClass(TunnelRequestMessage.class);
        verify(tunnelIngressService).forward(eq("tun-abc123"), sent.capture(), eq("{}"));
        assertEquals("", sent.getValue().getPath());
    }
}
