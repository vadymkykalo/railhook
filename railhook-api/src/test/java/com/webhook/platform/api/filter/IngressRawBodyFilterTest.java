package com.webhook.platform.api.filter;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Every public endpoint that hands a body on as sent — ingress to its Destinations, a tunnel to
 * the developer's machine, a test capture to the person reading it — needs the bytes kept before
 * anything parses the form out of the stream. Only {@code /ingress/} had them.
 */
class IngressRawBodyFilterTest {

    private static final byte[] FORM = "text=a%20b&token=X%2fY".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @ValueSource(strings = {"/ingress/tok_1", "/tunnel/tun-abc/slack/commands", "/tunnel/tun-abc", "/hook/abc"})
    void keepsTheBytesForEveryEndpointThatPassesABodyOn(String uri) throws Exception {
        MockHttpServletRequest request = formPost(uri);
        MockFilterChain chain = new MockFilterChain();

        new IngressRawBodyFilter().doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest downstream = (HttpServletRequest) chain.getRequest();
        assertNotSame(request, downstream, "the body is replayed from what was kept");
        assertArrayEquals(FORM, IngressRawBodyFilter.rawBody(downstream));
        assertArrayEquals(FORM, downstream.getInputStream().readAllBytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/projects", "/tunnelx/abc", "/hooks"})
    void leavesEveryOtherRequestAlone(String uri) throws Exception {
        MockHttpServletRequest request = formPost(uri);
        MockFilterChain chain = new MockFilterChain();

        new IngressRawBodyFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertSame(request, chain.getRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/tunnel/tun-abc", "/hook/abc"})
    void anEmptyBodyIsNone(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        MockFilterChain chain = new MockFilterChain();

        new IngressRawBodyFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(IngressRawBodyFilter.rawBody((HttpServletRequest) chain.getRequest()));
    }

    private static MockHttpServletRequest formPost(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent(FORM);
        return request;
    }
}
