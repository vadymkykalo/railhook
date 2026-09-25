package com.webhook.platform.api.controller;

import com.webhook.platform.api.filter.IngressRawBodyFilter;
import com.webhook.platform.api.service.TunnelIngressService;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import com.webhook.platform.common.dto.tunnel.TunnelResponseMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Only the bare slug was mapped, so tunnel sub-paths answered 404.
class TunnelIngressControllerTest {

    private TunnelIngressService tunnelIngressService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tunnelIngressService = mock(TunnelIngressService.class);
        when(tunnelIngressService.forward(anyString(), any(), any()))
                .thenReturn(new TunnelIngressService.Outcome.Refused("rate_limit_exceeded", "stop here"));
        mockMvc = MockMvcBuilders.standaloneSetup(new TunnelIngressController(tunnelIngressService))
                .addFilters(new IngressRawBodyFilter())
                .build();
    }

    @Test
    void forwardsAPathBelowTheSlugToTheTunnel() throws Exception {
        mockMvc.perform(post("/tunnel/tun-abc123/webhooks/stripe?attempt=2")
                        .contentType("application/json").content("{\"id\":\"evt_1\"}"))
                .andExpect(status().isTooManyRequests());

        ArgumentCaptor<TunnelRequestMessage> sent = ArgumentCaptor.forClass(TunnelRequestMessage.class);
        verify(tunnelIngressService).forward(eq("tun-abc123"), sent.capture(),
                aryEq("{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("/webhooks/stripe", sent.getValue().getPath());
        assertEquals("attempt=2", sent.getValue().getQueryString());
        assertEquals("{\"id\":\"evt_1\"}", sent.getValue().getBody());
    }

    // It answered 502, which a CDN replaces with its own "Bad gateway" page.
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
        verify(tunnelIngressService).forward(eq("tun-abc123"), sent.capture(),
                aryEq("{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("", sent.getValue().getPath());
    }

    // Binding the body as a String let Spring rebuild it from parsed parameters.
    @Test
    void aFormBodyReachesTheTunnelByteForByte() throws Exception {
        byte[] form = "text=a%20b&token=X%2fY".getBytes(StandardCharsets.US_ASCII);

        mockMvc.perform(post("/tunnel/tun-abc123/slack/commands")
                        .contentType("application/x-www-form-urlencoded").content(form))
                .andExpect(status().isTooManyRequests());

        ArgumentCaptor<TunnelRequestMessage> sent = ArgumentCaptor.forClass(TunnelRequestMessage.class);
        verify(tunnelIngressService).forward(eq("tun-abc123"), sent.capture(), aryEq(form));
        assertArrayEquals(form, sent.getValue().bodyBytes());
        assertEquals("text=a%20b&token=X%2fY", sent.getValue().getBody(), "an installed CLI reads this");
    }

    @Test
    void aBodyThatIsNotUtf8ReachesTheTunnelByteForByte() throws Exception {
        byte[] gzip = {(byte) 0x1f, (byte) 0x8b, 0x08, 0x00, (byte) 0xff, (byte) 0xfe, 0x00, (byte) 0x80};

        mockMvc.perform(post("/tunnel/tun-abc123/upload")
                        .contentType("application/json").header("Content-Encoding", "gzip").content(gzip))
                .andExpect(status().isTooManyRequests());

        ArgumentCaptor<TunnelRequestMessage> sent = ArgumentCaptor.forClass(TunnelRequestMessage.class);
        verify(tunnelIngressService).forward(eq("tun-abc123"), sent.capture(), aryEq(gzip));
        assertArrayEquals(gzip, sent.getValue().bodyBytes());
        assertEquals("gzip", sent.getValue().getHeaders().get("Content-Encoding"));
        assertEquals("application/json", sent.getValue().getHeaders().get("Content-Type"));
    }

    @Test
    void theLocalAppsBinaryResponseReachesTheCallerByteForByte() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, (byte) 0xff, 0x00};
        when(tunnelIngressService.forward(anyString(), any(), any()))
                .thenReturn(new TunnelIngressService.Outcome.Answered(TunnelResponseMessage.builder()
                        .statusCode(200)
                        .headers(Map.of("content-type", "image/png"))
                        .rawBody(png, StandardCharsets.UTF_8)
                        .build()));

        MvcResult result = mockMvc.perform(post("/tunnel/tun-abc123/logo").content("{}"))
                .andExpect(status().isOk())
                .andReturn();

        assertArrayEquals(png, result.getResponse().getContentAsByteArray());
        assertEquals("image/png", result.getResponse().getContentType());
    }

    // An installed CLI answers with the string alone, as it always has.
    @Test
    void aResponseFromAnInstalledCliIsRelayedAsBefore() throws Exception {
        when(tunnelIngressService.forward(anyString(), any(), any()))
                .thenReturn(new TunnelIngressService.Outcome.Answered(TunnelResponseMessage.builder()
                        .statusCode(201)
                        .headers(Map.of("content-type", "application/json"))
                        .body("{\"ok\":\"ü\"}")
                        .build()));

        MvcResult result = mockMvc.perform(post("/tunnel/tun-abc123/hook").content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertArrayEquals("{\"ok\":\"ü\"}".getBytes(StandardCharsets.UTF_8),
                result.getResponse().getContentAsByteArray());
    }

    @Test
    void aRefusalIsJson() throws Exception {
        MvcResult result = mockMvc.perform(post("/tunnel/tun-abc123").content("{}"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertEquals("application/json", result.getResponse().getContentType());
        assertEquals("{\"error\":\"rate_limit_exceeded\",\"message\":\"stop here\"}",
                result.getResponse().getContentAsString());
    }
}
