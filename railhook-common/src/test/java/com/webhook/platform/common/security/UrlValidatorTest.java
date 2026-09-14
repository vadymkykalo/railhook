package com.webhook.platform.common.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UrlValidatorTest {

    @Test
    void shouldAllowValidHttpUrl() {
        assertDoesNotThrow(() -> 
            UrlValidator.validateWebhookUrl("http://example.com/webhook", false, Collections.emptyList())
        );
    }

    @Test
    void shouldAllowValidHttpsUrl() {
        assertDoesNotThrow(() -> 
            UrlValidator.validateWebhookUrl("https://example.com/webhook", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectNonHttpScheme() {
        assertThrows(UrlValidator.InvalidUrlException.class, () -> 
            UrlValidator.validateWebhookUrl("ftp://example.com", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectFileScheme() {
        assertThrows(UrlValidator.InvalidUrlException.class, () -> 
            UrlValidator.validateWebhookUrl("file:///etc/passwd", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectLocalhostByDefault() {
        assertThrows(UrlValidator.InvalidUrlException.class, () -> 
            UrlValidator.validateWebhookUrl("http://localhost:8080", false, Collections.emptyList())
        );
    }

    @Test
    void shouldReject127001ByDefault() {
        assertThrows(UrlValidator.InvalidUrlException.class, () -> 
            UrlValidator.validateWebhookUrl("http://127.0.0.1", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectPrivate10Network() {
        assertThrows(UrlValidator.InvalidUrlException.class, () -> 
            UrlValidator.validateWebhookUrl("http://10.0.0.1", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectPrivate192168Network() {
        assertThrows(UrlValidator.InvalidUrlException.class, () -> 
            UrlValidator.validateWebhookUrl("http://192.168.1.1", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectPrivate172Network() {
        assertThrows(UrlValidator.InvalidUrlException.class, () -> 
            UrlValidator.validateWebhookUrl("http://172.16.0.1", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectLinkLocal() {
        assertThrows(UrlValidator.InvalidUrlException.class, () -> 
            UrlValidator.validateWebhookUrl("http://169.254.169.254", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectMetadataEndpoint() {
        assertThrows(UrlValidator.InvalidUrlException.class, () -> 
            UrlValidator.validateWebhookUrl("http://metadata.google.internal", false, Collections.emptyList())
        );
    }

    @Test
    void shouldAllowPrivateIpWhenConfigured() {
        assertDoesNotThrow(() -> 
            UrlValidator.validateWebhookUrl("http://192.168.1.1", true, Collections.emptyList())
        );
    }

    @Test
    void shouldAllowWhitelistedHost() {
        assertDoesNotThrow(() -> 
            UrlValidator.validateWebhookUrl("http://test-receiver:8082/webhook", false, 
                List.of("test-receiver"))
        );
    }

    @Test
    void shouldRejectNullUrl() {
        assertThrows(UrlValidator.InvalidUrlException.class, () -> 
            UrlValidator.validateWebhookUrl(null, false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectEmptyUrl() {
        assertThrows(UrlValidator.InvalidUrlException.class, () -> 
            UrlValidator.validateWebhookUrl("", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectUrlWithoutHost() {
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://", false, Collections.emptyList())
        );
    }

    // -----------------------------------------------------------------
    // Previously-missing CIDR ranges and metadata addresses
    // -----------------------------------------------------------------

    @Test
    void shouldRejectCgnatRangeStart() {
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://100.64.0.1", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectCgnatRangeEnd() {
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://100.127.255.255", false, Collections.emptyList())
        );
    }

    @Test
    void shouldAllowJustBelowCgnatRange() {
        assertDoesNotThrow(() ->
            UrlValidator.validateWebhookUrl("http://100.63.255.255", false, Collections.emptyList())
        );
    }

    @Test
    void shouldAllowJustAboveCgnatRange() {
        assertDoesNotThrow(() ->
            UrlValidator.validateWebhookUrl("http://100.128.0.1", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectAlibabaMetadataAddress() {
        // 100.100.100.200 falls inside 100.64.0.0/10 (CGNAT) AND is hard-blocked by
        // hostname via BLOCKED_HOSTS.
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://100.100.100.200", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectAlibabaMetadataAddress_evenWhenAllowlisted() {
        // BLOCKED_HOSTS is checked before the allowedHosts bypass — a known cloud
        // metadata address can never be legitimately allow-listed.
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://100.100.100.200", false, List.of("100.100.100.200"))
        );
    }

    @Test
    void shouldRejectIetfProtocolAssignments() {
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://192.0.0.1", false, Collections.emptyList())
        );
    }

    @Test
    void shouldAllowJustOutsideIetfProtocolAssignmentsBlock() {
        // 192.0.0.0/24 only — 192.0.1.x is outside it.
        assertDoesNotThrow(() ->
            UrlValidator.validateWebhookUrl("http://192.0.1.1", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectBenchmarkingRangeStart() {
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://198.18.0.1", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectBenchmarkingRangeEnd() {
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://198.19.255.254", false, Collections.emptyList())
        );
    }

    @Test
    void shouldAllowJustBelowBenchmarkingRange() {
        assertDoesNotThrow(() ->
            UrlValidator.validateWebhookUrl("http://198.17.255.255", false, Collections.emptyList())
        );
    }

    @Test
    void shouldAllowJustAboveBenchmarkingRange() {
        assertDoesNotThrow(() ->
            UrlValidator.validateWebhookUrl("http://198.20.0.1", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectMulticastRangeStart() {
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://224.0.0.1", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectMulticastRangeEnd() {
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://239.255.255.255", false, Collections.emptyList())
        );
    }

    @Test
    void shouldAllowJustBelowMulticastRange() {
        assertDoesNotThrow(() ->
            UrlValidator.validateWebhookUrl("http://223.255.255.255", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectReservedRange() {
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://240.0.0.1", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectBroadcastAddress() {
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://255.255.255.255", false, Collections.emptyList())
        );
    }

    @Test
    void shouldAllowAllNewlyBlockedRangesWhenPrivateIpsAllowed() {
        // allowPrivateIps=true is the general opt-out for the whole private/special
        // range check, including the newly-added ranges.
        assertDoesNotThrow(() ->
            UrlValidator.validateWebhookUrl("http://100.64.0.1", true, Collections.emptyList())
        );
        assertDoesNotThrow(() ->
            UrlValidator.validateWebhookUrl("http://198.18.0.1", true, Collections.emptyList())
        );
        assertDoesNotThrow(() ->
            UrlValidator.validateWebhookUrl("http://224.0.0.1", true, Collections.emptyList())
        );
        assertDoesNotThrow(() ->
            UrlValidator.validateWebhookUrl("http://240.0.0.1", true, Collections.emptyList())
        );
    }
    // ── The post-connect half of the same decision ──────────────────────────────
    //
    // validateWebhookUrl runs at admission; SsrfProtectionCustomizer re-runs the check
    // against the address the socket actually connected to, which is what closes the DNS
    // rebinding window. Those two have to agree, and they did not: admission honoured
    // allowedHosts and the post-connect check did not know the list existed, so an operator
    // who allow-listed one internal host got every delivery to it killed at the TCP layer
    // with nothing in the config to explain why.

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
        // Same precedence as admission: BLOCKED_HOSTS wins over the allow list. Putting the
        // metadata address on it must not be a way to reach cloud credentials.
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

    // ── IPv6 forms of addresses the IPv4 checks already refuse ──────────────────
    //
    // The IPv6 check knew three prefixes and nothing else. "::" is the unspecified address, and
    // a connect() to it lands on the local host just as 0.0.0.0 does — so http://[::]:8080 went
    // straight past a guard that refuses http://0.0.0.0:8080. The translation prefixes carry an
    // IPv4 address inside them, and a host behind NAT64 or a 6to4 relay reaches that address,
    // so they have to be judged by the address they carry.

    @Test
    void shouldRejectTheUnspecifiedIpv6Address() {
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://[::]:8080/hook", false, Collections.emptyList())
        );
    }

    @Test
    void shouldRejectIpv6Loopback() {
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://[::1]/hook", false, Collections.emptyList())
        );
    }

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
    void shouldRejectNat64LiteralCarryingAMetadataAddress() {
        assertThrows(UrlValidator.InvalidUrlException.class, () ->
            UrlValidator.validateWebhookUrl("http://[64:ff9b::a9fe:a9fe]/latest/meta-data", false,
                    Collections.emptyList())
        );
    }

    @Test
    void ipv6MulticastIsBlockedAsIpv4MulticastIs() throws Exception {
        assertTrue(UrlValidator.isPrivateOrLocalAddress(ipv6("ff02::1")));
    }

    @Test
    void anOrdinaryGlobalIpv6AddressIsAllowed() throws Exception {
        assertFalse(UrlValidator.isPrivateOrLocalAddress(ipv6("2606:2800:220:1:248:1893:25c8:1946")));
    }

    @Test
    void postConnectRejectsTheUnspecifiedIpv6Address() throws Exception {
        assertTrue(UrlValidator.isBlockedTarget("[::]", InetAddress.getByName("::"), false, Collections.emptyList()));
    }

    // ── a name that does not resolve is not a name that is refused ──────────────

    @Test
    void anUnresolvableHostIsReportedAsSuchAndNotAsABlockedTarget() {
        // .invalid is reserved never to resolve. Failing to resolve says nothing about where the
        // name points, so the delivery path has to be able to tell this apart from a refusal.
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

    /** Built from bytes: InetAddress.getByName folds ::ffff:a.b.c.d into an Inet4Address. */
    private static InetAddress ipv6Mapped(int a, int b, int c, int d) throws Exception {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        return java.net.Inet6Address.getByAddress(null, bytes, -1);
    }

    private static InetAddress ipv6(String literal) throws Exception {
        InetAddress address = InetAddress.getByName(literal);
        assertEquals(16, address.getAddress().length, literal + " should parse as IPv6");
        return address;
    }
}
