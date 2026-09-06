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
}
