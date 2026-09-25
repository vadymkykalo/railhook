package com.webhook.platform.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.repository.TestEndpointRepository;
import com.webhook.platform.api.dto.CapturedRequestResponse;
import com.webhook.platform.api.filter.IngressRawBodyFilter;
import com.webhook.platform.api.service.RedisRateLimiterService;
import com.webhook.platform.api.service.TestEndpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// A form POST bound as a String was rebuilt from parsed params, so the capture showed a request nobody sent.
class WebhookCaptureControllerTest {

    private TestEndpointService testEndpointService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        testEndpointService = mock(TestEndpointService.class);
        TestEndpointRepository repository = mock(TestEndpointRepository.class);
        RedisRateLimiterService rateLimiter = mock(RedisRateLimiterService.class);
        when(repository.existsBySlug("abc")).thenReturn(true);
        when(rateLimiter.tryAcquireForSlug(anyString(), anyInt())).thenReturn(true);
        when(testEndpointService.captureRequest(anyString(), any(), any()))
                .thenReturn(CapturedRequestResponse.builder().id("cap_1").build());
        mockMvc = MockMvcBuilders.standaloneSetup(new WebhookCaptureController(
                        testEndpointService, repository, new ObjectMapper(), rateLimiter))
                .addFilters(new IngressRawBodyFilter())
                .build();
    }

    @Test
    void aFormBodyIsCapturedAsItWasSent() throws Exception {
        mockMvc.perform(post("/hook/abc").contentType("application/x-www-form-urlencoded")
                        .content("text=a%20b&token=X%2fY".getBytes(StandardCharsets.US_ASCII)))
                .andExpect(status().isOk());

        verify(testEndpointService).captureRequest(eq("abc"), eq("text=a%20b&token=X%2fY"), any());
    }

    @Test
    void aBodyIsDecodedWithTheCharsetItDeclares() throws Exception {
        mockMvc.perform(post("/hook/abc").contentType("text/plain; charset=ISO-8859-1")
                        .content("café".getBytes(StandardCharsets.ISO_8859_1)))
                .andExpect(status().isOk());

        verify(testEndpointService).captureRequest(eq("abc"), eq("café"), any());
    }

    @Test
    void aVerificationChallengeIsStillAnswered() throws Exception {
        mockMvc.perform(post("/hook/abc").contentType("application/json")
                        .content("{\"type\":\"webhook.verification\",\"challenge\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(MockMvcResultMatchers
                        .jsonPath("$.challenge").value("c1"));
    }
}
