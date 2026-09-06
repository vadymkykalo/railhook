package com.webhook.platform.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequestSizeLimitFilterTest {

    private static final long MAX_SIZE = 100; // 100 bytes max for testing
    private static final long INGRESS_MAX_SIZE = 200; // 200 bytes max for ingress

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private RequestSizeLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestSizeLimitFilter(MAX_SIZE, INGRESS_MAX_SIZE);
    }

    @Test
    void shouldRejectWhenContentLengthExceedsLimit() throws Exception {
        // Content-Length header reports oversized body
        when(request.getContentLengthLong()).thenReturn(MAX_SIZE + 1);
        when(request.getRequestURI()).thenReturn("/api/events");
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(413);
        verify(filterChain, never()).doFilter(any(), any());
        assertTrue(sw.toString().contains("payload_too_large"));
    }

    @Test
    void shouldAllowSmallPayloadWithContentLength() throws Exception {
        byte[] payload = "small".getBytes();
        when(request.getContentLengthLong()).thenReturn((long) payload.length);
        when(request.getRequestURI()).thenReturn("/api/events");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(any(), eq(response));
        verify(response, never()).setStatus(413);
    }

    @Test
    void shouldRejectChunkedTransferExceedingLimit() throws Exception {
        // Chunked transfer — Content-Length is -1
        byte[] oversizedPayload = new byte[(int) MAX_SIZE + 50];
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getInputStream()).thenReturn(mockServletInputStream(oversizedPayload));
        when(request.getRequestURI()).thenReturn("/api/events");
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        // Simulate the filter chain reading the full body
        doAnswer(invocation -> {
            HttpServletRequest req = invocation.getArgument(0);
            byte[] buf = new byte[1024];
            var is = req.getInputStream();
            while (is.read(buf) != -1) {
                // exhaust stream
            }
            return null;
        }).when(filterChain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(413);
        assertTrue(sw.toString().contains("payload_too_large"));
    }

    @Test
    void shouldAllowChunkedTransferWithinLimit() throws Exception {
        byte[] payload = "within limit".getBytes();
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getRequestURI()).thenReturn("/api/events");
        when(request.getInputStream()).thenReturn(mockServletInputStream(payload));

        doAnswer(invocation -> {
            HttpServletRequest req = invocation.getArgument(0);
            byte[] buf = new byte[1024];
            var is = req.getInputStream();
            while (is.read(buf) != -1) {
                // exhaust stream
            }
            return null;
        }).when(filterChain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));

        filter.doFilterInternal(request, response, filterChain);

        verify(response, never()).setStatus(413);
    }

    @Test
    void shouldPassThroughRequestsWithNoBody() throws Exception {
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getRequestURI()).thenReturn("/api/events");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(any(), eq(response));
        verify(response, never()).setStatus(413);
    }

    @Test
    void shouldUseIngressLimitForIngressPath() throws Exception {
        // 150 bytes > MAX_SIZE (100) but < INGRESS_MAX_SIZE (200)
        byte[] payload = new byte[150];
        when(request.getContentLengthLong()).thenReturn((long) payload.length);
        when(request.getRequestURI()).thenReturn("/ingress/abc123");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(any(), eq(response));
        verify(response, never()).setStatus(413);
    }

    @Test
    void shouldRejectIngressPayloadExceedingIngressLimit() throws Exception {
        // 250 bytes > INGRESS_MAX_SIZE (200)
        when(request.getContentLengthLong()).thenReturn(250L);
        when(request.getRequestURI()).thenReturn("/ingress/abc123");
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(413);
        verify(filterChain, never()).doFilter(any(), any());
        assertTrue(sw.toString().contains("payload_too_large"));
    }

    private ServletInputStream mockServletInputStream(byte[] data) {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        return new ServletInputStream() {
            @Override
            public int read() throws IOException {
                return bais.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return bais.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // no-op
            }
        };
    }
}
