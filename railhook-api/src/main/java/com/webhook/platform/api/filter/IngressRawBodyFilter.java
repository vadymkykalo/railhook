package com.webhook.platform.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Keeps the exact bytes of an ingress body, since signatures are computed over them. For a form
 * POST, Spring rebuilds the body from parsed parameters and re-encodes it ({@code %20} becomes
 * {@code +}), which breaks a correct signature. Once anything reads a parameter the stream is
 * consumed, so this runs before every filter that might.
 *
 * <p>Ordered right after {@code RequestSizeLimitFilter}, so reading the whole body is still
 * bounded by the size limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class IngressRawBodyFilter extends OncePerRequestFilter {

    private static final String RAW_BODY_ATTRIBUTE = IngressRawBodyFilter.class.getName() + ".rawBody";

    /** Null for an empty body. Falls back to the stream for a request this filter skipped. */
    public static byte[] rawBody(HttpServletRequest request) throws IOException {
        byte[] raw = (byte[]) request.getAttribute(RAW_BODY_ATTRIBUTE);
        if (raw == null) {
            raw = request.getInputStream().readAllBytes();
        }
        return raw.length == 0 ? null : raw;
    }

    private static final List<String> RAW_BODY_PREFIXES = List.of("/ingress/", "/tunnel/", "/hook/");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || RAW_BODY_PREFIXES.stream().noneMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        byte[] raw = request.getInputStream().readAllBytes();
        request.setAttribute(RAW_BODY_ATTRIBUTE, raw);
        filterChain.doFilter(new CachedBodyRequest(request, raw), response);
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
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
                    throw new UnsupportedOperationException("The body has already been read");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
