package com.webhook.platform.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// X-Forwarded-For was trusted verbatim, and its left-most, most attacker-controlled hop was used.
class TrustedProxyResolverTest {

    @ParameterizedTest(name = "trusted={0} peer={1} xff={2} -> {3}")
    @CsvSource(delimiter = '|', value = {
            "                   | 8.8.8.8     | 1.2.3.4                         | 8.8.8.8",
            "10.0.0.1           | 203.0.113.9 | 1.2.3.4                         | 203.0.113.9",
            "10.0.0.1           | 10.0.0.1    | 198.51.100.7                    | 198.51.100.7",
            "10.0.0.1 10.0.0.2  | 10.0.0.2    | 6.6.6.6, 1.2.3.4, 10.0.0.1      | 1.2.3.4",
            "10.0.0.1           | 10.0.0.1    | 9.9.9.9, 8.8.8.8, 203.0.113.55  | 203.0.113.55",
            "10.0.0.1 10.0.0.2  | 10.0.0.2    | 10.0.0.5, 10.0.0.1              | 10.0.0.5",
            "172.16.0.0/12      | 172.20.5.5  | 198.51.100.20                   | 198.51.100.20",
            "172.16.0.0/12      | 192.168.1.1 | 198.51.100.20                   | 192.168.1.1",
            "10.0.0.1           | 10.0.0.1    | not-a-real-hostname.example.com | not-a-real-hostname.example.com",
    })
    void resolvesTheRightMostUntrustedHopAndOnlyBehindATrustedPeer(
            String trusted, String peer, String xForwardedFor, String expected) {
        TrustedProxyResolver resolver = new TrustedProxyResolver(proxies(trusted));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(peer);
        when(request.getHeader("X-Forwarded-For")).thenReturn(xForwardedFor);

        assertEquals(expected, resolver.resolve(request));
    }

    @Test
    void xRealIpIsUsedWhenXffIsAbsentAndThePeerIsTrusted() {
        TrustedProxyResolver resolver = new TrustedProxyResolver(List.of("10.0.0.1"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.42");

        assertEquals("198.51.100.42", resolver.resolve(request));
    }

    private static List<String> proxies(String trusted) {
        return trusted == null ? List.of() : Arrays.asList(trusted.trim().split("\\s+"));
    }
}
