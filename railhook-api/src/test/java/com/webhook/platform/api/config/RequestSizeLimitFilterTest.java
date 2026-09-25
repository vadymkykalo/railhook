package com.webhook.platform.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RequestSizeLimitFilterTest {

    private static final long MAX_SIZE = 100;
    private static final long INGRESS_MAX_SIZE = 200;

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private RequestSizeLimitFilter filter;
    private StringWriter body;

    @BeforeEach
    void setUp() throws Exception {
        filter = new RequestSizeLimitFilter(MAX_SIZE, INGRESS_MAX_SIZE);
        body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
    }

    @ParameterizedTest
    @CsvSource({
            "/api/events,     101, true",
            "/api/events,     5,   false",
            "/ingress/abc123, 150, false",
            "/ingress/abc123, 250, true",
    })
    void aDeclaredLengthIsCheckedAgainstThePathsLimit(String uri, long contentLength, boolean rejected) throws Exception {
        when(request.getContentLengthLong()).thenReturn(contentLength);
        when(request.getRequestURI()).thenReturn(uri);

        filter.doFilterInternal(request, response, filterChain);

        if (rejected) {
            verify(response).setStatus(413);
            verify(filterChain, never()).doFilter(any(), any());
            assertTrue(body.toString().contains("payload_too_large"));
        } else {
            verify(filterChain).doFilter(any(), any());
            verify(response, never()).setStatus(413);
        }
    }

    @ParameterizedTest
    @CsvSource({"150, true", "12, false"})
    void aChunkedBodyIsCountedAsItIsRead(int size, boolean rejected) throws Exception {
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getRequestURI()).thenReturn("/api/events");
        when(request.getInputStream()).thenReturn(inputStream(new byte[size]));
        doAnswer(invocation -> {
            HttpServletRequest req = invocation.getArgument(0);
            req.getInputStream().readAllBytes();
            return null;
        }).when(filterChain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));

        filter.doFilterInternal(request, response, filterChain);

        if (rejected) {
            verify(response).setStatus(413);
        } else {
            verify(response, never()).setStatus(413);
        }
    }

    @Test
    void aRequestWithNoBodyPassesThrough() throws Exception {
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getRequestURI()).thenReturn("/api/events");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
        verify(response, never()).setStatus(413);
    }

    private static ServletInputStream inputStream(byte[] data) {
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        return new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return in.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
            }
        };
    }
}
