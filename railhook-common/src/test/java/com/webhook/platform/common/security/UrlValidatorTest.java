package com.webhook.platform.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/webhook",
            "https://example.com/webhook",
            "http://100.63.255.255",
            "http://100.128.0.1",
            "http://192.0.1.1",
            "http://198.17.255.255",
            "http://198.20.0.1",
            "http://223.255.255.255",
    })
    void allowsAPublicHttpTarget(String url) {
        assertDoesNotThrow(() -> UrlValidator.validateWebhookUrl(url, false, Collections.emptyList()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "http://",
            "ftp://example.com",
            "file:///etc/passwd",
            "http://localhost:8080",
            "http://127.0.0.1",
            "http://10.0.0.1",
            "http://192.168.1.1",
            "http://172.16.0.1",
            "http://169.254.169.254",
            "http://metadata.google.internal",
            "http://100.64.0.1",
            "http://100.127.255.255",
            "http://100.100.100.200",
            "http://192.0.0.1",
            "http://198.18.0.1",
            "http://198.19.255.254",
            "http://224.0.0.1",
            "http://239.255.255.255",
            "http://240.0.0.1",
            "http://255.255.255.255",
            "http://[::]:8080/hook",
            "http://[::1]/hook",
            "http://[64:ff9b::a9fe:a9fe]/latest/meta-data",
    })
    void rejectsAMalformedNonHttpPrivateOrSpecialTarget(String url) {
        assertThrows(UrlValidator.InvalidUrlException.class,
                () -> UrlValidator.validateWebhookUrl(url, false, Collections.emptyList()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://192.168.1.1", "http://100.64.0.1", "http://198.18.0.1", "http://224.0.0.1", "http://240.0.0.1",
    })
    void allowPrivateIpsOptsOutOfEveryRangeCheck(String url) {
        assertDoesNotThrow(() -> UrlValidator.validateWebhookUrl(url, true, Collections.emptyList()));
    }

    @Test
    void shouldAllowWhitelistedHost() {
        assertDoesNotThrow(() ->
            UrlValidator.validateWebhookUrl("http://test-receiver:8082/webhook", false,
                List.of("test-receiver"))
        );
    }

    @Test
    void shouldRejectAlibabaMetadataAddress_evenWhenAllowlisted() {
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://100.100.100.200", false, List.of("100.100.100.200"))
        );
    }

    // Admission honoured allowedHosts and the post-connect check did not, killing allow-listed deliveries.
    @Test
    void postConnectRejectsAPrivateAddressWhenNothingIsAllowListed() throws Exception {
        assertTrue(UrlValidator.isBlockedTarget(
                "internal.example.com", InetAddress.getByName("10.1.2.3"), false, Collections.emptyList()));
    }

    @Test
    void postConnectHonoursTheAllowListTheAdmissionCheckHonours() throws Exception {
        assertFalse(UrlValidator.isBlockedTarget(
                "internal.example.com", InetAddress.getByName("10.1.2.3"), false,
                List.of("internal.example.com")));
    }

    @Test
    void postConnectAllowListDoesNotCoverAHostThatIsNotOnIt() throws Exception {
        assertTrue(UrlValidator.isBlockedTarget(
                "other.example.com", InetAddress.getByName("10.1.2.3"), false,
                List.of("internal.example.com")));
    }

    @Test
    void postConnectNeverAllowListsAMetadataEndpoint() throws Exception {
        assertTrue(UrlValidator.isBlockedTarget(
                "169.254.169.254", InetAddress.getByName("169.254.169.254"), false,
                List.of("169.254.169.254")));
    }

    @Test
    void postConnectAllowsAPublicAddressWithoutAnyList() throws Exception {
        assertFalse(UrlValidator.isBlockedTarget(
                "example.com", InetAddress.getByName("93.184.216.34"), false, Collections.emptyList()));
    }

    @Test
    void postConnectAllowsAPrivateAddressWhenPrivateIpsAreAllowedOutright() throws Exception {
        assertFalse(UrlValidator.isBlockedTarget(
                "internal.example.com", InetAddress.getByName("10.1.2.3"), true, Collections.emptyList()));
    }

    @Test
    void postConnectRejectsTheUnspecifiedIpv6Address() throws Exception {
        assertTrue(UrlValidator.isBlockedTarget("[::]", InetAddress.getByName("::"), false, Collections.emptyList()));
    }

    // A translation prefix reaches the IPv4 address it carries, so it is judged by that address.
    @Test
    void unspecifiedAndIpv4CompatibleAddressesAreBlockedPostConnect() throws Exception {
        assertTrue(UrlValidator.isPrivateOrLocalAddress(InetAddress.getByName("::")));
        assertTrue(UrlValidator.isPrivateOrLocalAddress(InetAddress.getByName("0.0.0.0")));
        assertTrue(UrlValidator.isPrivateOrLocalAddress(ipv6("0:0:0:0:0:0:a00:1")),
                "the deprecated IPv4-compatible form ::10.0.0.1 is not a public target");
    }

    @Test
    void ipv4MappedAddressesAreJudgedByTheAddressTheyCarry() throws Exception {
        assertTrue(UrlValidator.isPrivateOrLocalAddress(ipv6Mapped(10, 0, 0, 1)));
        assertTrue(UrlValidator.isPrivateOrLocalAddress(ipv6Mapped(127, 0, 0, 1)));
        assertTrue(UrlValidator.isPrivateOrLocalAddress(ipv6Mapped(169, 254, 169, 254)));
        assertFalse(UrlValidator.isPrivateOrLocalAddress(ipv6Mapped(93, 184, 216, 34)));
    }

    @Test
    void nat64AddressesAreJudgedByTheAddressTheyCarry() throws Exception {
        assertTrue(UrlValidator.isPrivateOrLocalAddress(ipv6("64:ff9b::a00:1")), "64:ff9b::10.0.0.1");
        assertTrue(UrlValidator.isPrivateOrLocalAddress(ipv6("64:ff9b::a9fe:a9fe")), "64:ff9b::169.254.169.254");
        assertTrue(UrlValidator.isPrivateOrLocalAddress(ipv6("64:ff9b::7f00:1")), "64:ff9b::127.0.0.1");
        assertFalse(UrlValidator.isPrivateOrLocalAddress(ipv6("64:ff9b::5db8:d822")), "64:ff9b::93.184.216.34");
    }

    @Test
    void sixToFourAddressesAreJudgedByTheAddressTheyCarry() throws Exception {
        assertTrue(UrlValidator.isPrivateOrLocalAddress(ipv6("2002:a00:1::1")), "6to4 of 10.0.0.1");
        assertTrue(UrlValidator.isPrivateOrLocalAddress(ipv6("2002:c0a8:101::")), "6to4 of 192.168.1.1");
        assertTrue(UrlValidator.isPrivateOrLocalAddress(ipv6("2002:7f00:1::")), "6to4 of 127.0.0.1");
        assertFalse(UrlValidator.isPrivateOrLocalAddress(ipv6("2002:5db8:d822::1")), "6to4 of 93.184.216.34");
    }

    @Test
    void ipv6MulticastIsBlockedAndAnOrdinaryGlobalAddressIsNot() throws Exception {
        assertTrue(UrlValidator.isPrivateOrLocalAddress(ipv6("ff02::1")));
        assertFalse(UrlValidator.isPrivateOrLocalAddress(ipv6("2606:2800:220:1:248:1893:25c8:1946")));
    }

    // Failing to resolve says nothing about where a name points, so it must not read as a refusal.
    @Test
    void anUnresolvableHostIsReportedAsSuchAndNotAsABlockedTarget() {
        UrlValidator.InvalidUrlException e = assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("https://no-such-host.invalid/hook", false, Collections.emptyList())
        );
        assertInstanceOf(UrlValidator.UnresolvableHostException.class, e);
    }

    @Test
    void aRefusedAddressIsNotReportedAsUnresolvable() {
        UrlValidator.InvalidUrlException e = assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://10.0.0.1/hook", false, Collections.emptyList())
        );
        assertFalse(e instanceof UrlValidator.UnresolvableHostException);
    }

    // InetAddress.getByName folds ::ffff:a.b.c.d into an Inet4Address, so build it from bytes.
    private static InetAddress ipv6Mapped(int a, int b, int c, int d) throws Exception {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        return Inet6Address.getByAddress(null, bytes, -1);
    }

    private static InetAddress ipv6(String literal) throws Exception {
        InetAddress address = InetAddress.getByName(literal);
        assertEquals(16, address.getAddress().length, literal + " should parse as IPv6");
        return address;
    }
}
