package com.webhook.platform.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Regression coverage: X-Forwarded-For was trusted verbatim with no
 * proxy gate, and the naive left-most-hop parsing picked the most
 * attacker-controlled entry in a multi-hop chain.
 */
class TrustedProxyResolverTest {

    private static HttpServletRequest requestWith(String remoteAddr, String xForwardedFor) {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getHeader("X-Forwarded-For")).thenReturn(xForwardedFor);
        return request;
    }

    @Test
    void defaultConfiguration_trustsNothing() {
        TrustedProxyResolver resolver = new TrustedProxyResolver(Collections.emptyList());

        assertEquals("8.8.8.8",
                resolver.resolve(requestWith("8.8.8.8", "1.2.3.4")),
                "with no trusted proxies configured, XFF must never be consulted");
    }

    @Test
    void spoofedXff_fromUntrustedPeer_isIgnored() {
        TrustedProxyResolver resolver = new TrustedProxyResolver(List.of("10.0.0.1"));

        // Peer connecting directly is NOT the configured trusted proxy -- attacker
        // can put whatever they want in the header, it must be ignored entirely.
        HttpServletRequest request = requestWith("203.0.113.9", "1.2.3.4");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void genuineXff_fromConfiguredTrustedProxy_isHonoured() {
        TrustedProxyResolver resolver = new TrustedProxyResolver(List.of("10.0.0.1"));

        HttpServletRequest request = requestWith("10.0.0.1", "198.51.100.7");

        assertEquals("198.51.100.7", resolver.resolve(request));
    }

    @Test
    void multiHopChain_returnsRightMostUntrustedHop_notLeftMost() {
        // Trust two internal hops: an internal LB (10.0.0.1) fronted by an edge
        // proxy (10.0.0.2) that is itself the direct peer.
        TrustedProxyResolver resolver = new TrustedProxyResolver(List.of("10.0.0.1", "10.0.0.2"));

        // Chain as appended by each hop: real client, then each trusted hop appends
        // the address it received the request from.
        HttpServletRequest request = requestWith("10.0.0.2",
                "6.6.6.6, 1.2.3.4, 10.0.0.1");

        // Walking from the right: 10.0.0.1 is trusted (skip), 1.2.3.4 is the first
        // untrusted hop -- that is the real client, NOT the left-most "6.6.6.6"
        // (which is itself attacker-suppliable padding on a chain that reaches a
        // trusted proxy).
        assertEquals("1.2.3.4", resolver.resolve(request));
    }

    @Test
    void multiHopChain_attackerPrependsFakeHops_leftMostIsNotTrusted() {
        TrustedProxyResolver resolver = new TrustedProxyResolver(List.of("10.0.0.1"));

        // Attacker connects through the one real trusted proxy but pads the header
        // with fabricated left-most entries, hoping a naive split(",")[0] reader
        // picks their forged value instead of what the proxy actually appended.
        HttpServletRequest request = requestWith("10.0.0.1",
                "9.9.9.9, 8.8.8.8, 203.0.113.55");

        // Right-most is "203.0.113.55" which is what the trusted proxy actually saw
        // as its peer -- that is the real client, regardless of the forged prefix.
        assertEquals("203.0.113.55", resolver.resolve(request));
    }

    @Test
    void fullyTrustedChain_fallsBackToLeftMostOriginalEntry() {
        TrustedProxyResolver resolver = new TrustedProxyResolver(List.of("10.0.0.1", "10.0.0.2"));

        HttpServletRequest request = requestWith("10.0.0.2", "10.0.0.5, 10.0.0.1");
        // Every hop in the header is itself a trusted address -- nothing untrusted
        // to stop on, so fall back to the original left-most entry.
        assertEquals("10.0.0.5", resolver.resolve(request));
    }

    @Test
    void cidrRange_matchesTrustedProxy() {
        TrustedProxyResolver resolver = new TrustedProxyResolver(List.of("172.16.0.0/12"));

        HttpServletRequest request = requestWith("172.20.5.5", "198.51.100.20");
        assertEquals("198.51.100.20", resolver.resolve(request));

        HttpServletRequest outsideCidr = requestWith("192.168.1.1", "198.51.100.20");
        assertEquals("192.168.1.1", resolver.resolve(outsideCidr));
    }

    @Test
    void nonLiteralHopValue_doesNotTriggerDnsLookup_andIsTreatedAsUntrusted() {
        TrustedProxyResolver resolver = new TrustedProxyResolver(List.of("10.0.0.1"));

        HttpServletRequest request = requestWith("10.0.0.1", "not-a-real-hostname.example.com");

        // Must resolve promptly (no real DNS lookup attempted) and be treated as
        // the (untrusted, garbage) client value rather than throwing or hanging.
        assertEquals("not-a-real-hostname.example.com", resolver.resolve(request));
    }

    @Test
    void xRealIp_usedWhenXffAbsent_andPeerTrusted() {
        TrustedProxyResolver resolver = new TrustedProxyResolver(List.of("10.0.0.1"));
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.42");

        assertEquals("198.51.100.42", resolver.resolve(request));
    }

    @Test
    void isTrustedProxy_rejectsBlankTrustedProxyList() {
        TrustedProxyResolver resolver = new TrustedProxyResolver(List.of());
        assertEquals(false, resolver.isTrustedProxy("10.0.0.1"));
    }
}
