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
 * Keeps the bytes an ingress request arrived with, before anything can turn them into parameters.
 *
 * <p>A provider signs the body it put on the wire, so verification, the stored copy and the
 * forwarded copy all have to be those bytes. For a form POST without a query string Spring's
 * message conversion does not read them: {@code ServletServerHttpRequest.getBody()} rebuilds a
 * body from the container's parsed parameters, re-encoded as Java's URLEncoder would encode it —
 * {@code %20} becomes {@code +}, lowercase hex becomes uppercase — and a Slack command, a GitHub
 * form delivery or a form-posting HMAC sender was refused with 401 over a signature that was
 * correct. And once anything has asked for a parameter, the container has consumed the stream and
 * the original bytes are gone, so this has to run before every filter that might.
 *
 * <p>The same holds for a tunnel, where the developer's own app does the verifying, and for a test
 * capture, which is only useful if it shows what was sent — see {@code RAW_BODY_PREFIXES}.
 *
 * <p>Ordered right after {@code RequestSizeLimitFilter}, so reading the whole body here is still
 * bounded by that path's size limit: an oversized body fails mid-read and that filter answers 413.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class IngressRawBodyFilter extends OncePerRequestFilter {

    private static final String RAW_BODY_ATTRIBUTE = IngressRawBodyFilter.class.getName() + ".rawBody";

    /**
     * The body exactly as it arrived, null when there was none — which is what the ingress
     * controller received when it bound the body itself, and what {@code IngressService} stores.
     * A request this filter did not run for is read from its stream instead.
     */
    public static byte[] rawBody(HttpServletRequest request) throws IOException {
        byte[] raw = (byte[]) request.getAttribute(RAW_BODY_ATTRIBUTE);
        if (raw == null) {
            raw = request.getInputStream().readAllBytes();
        }
        return raw.length == 0 ? null : raw;
    }

    /**
     * The public endpoints that pass a body on as it was sent: ingress to Destinations, a tunnel
     * to the developer's local app — which checks the provider's signature itself — and a test
     * capture to the person reading what their provider sends.
     */
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

    /** Replays the kept bytes to anything downstream that reads the body itself. */
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
