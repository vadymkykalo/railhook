package com.webhook.platform.common.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * SSRF guard for outbound webhook/forward URLs.
 *
 * <p>This is a denylist of RFC 5735/6890 special-purpose IPv4/IPv6 ranges,
 * kept as a denylist rather than inverted to an allowlist of globally-routable
 * unicast space. Considered and rejected for now: an allowlist would need to track
 * IANA's registry as new blocks get carved out of previously-reserved space (this
 * denylist only grows the other, much rarer direction — new special-purpose
 * allocations), and a webhook-delivery hot path is a risky place to introduce
 * false-positive rejections of legitimate-but-newly-routable targets. The practical
 * need an allowlist would serve — an operator knowingly forwarding to an internal
 * service — is already met by {@code WEBHOOK_ALLOW_PRIVATE_IPS} plus the per-endpoint
 * allowed-hosts list. Revisit as a dedicated follow-up if the denylist keeps needing
 * new entries.
 */
public class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    
    // Hard-blocked regardless of allowedHosts (see validateWebhookUrl: this check runs
    // before the allow-list bypass) — nobody has a legitimate reason to forward a
    // webhook to a cloud metadata endpoint.
    private static final List<String> BLOCKED_HOSTS = List.of(
            "metadata.google.internal",
            // AWS, Azure, and Oracle Cloud (OCI) all serve their instance-metadata
            // service on this same well-known link-local address.
            "169.254.169.254",
            // Alibaba Cloud's metadata service. Also covered by the 100.64.0.0/10
            // CGNAT range in isPrivateIPv4 below, but listed explicitly so it's
            // blocked unconditionally rather than only when private IPs are blocked.
            "100.100.100.200"
    );

    // DNS resolution cache: 10min TTL, max 1000 entries
    private static final Cache<String, InetAddress[]> DNS_CACHE = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1000)
            .build();

    public static void validateWebhookUrl(String url, boolean allowPrivateIps, List<String> allowedHosts) {
        if (url == null || url.trim().isEmpty()) {
            throw new InvalidUrlException("URL cannot be null or empty");
        }

        try {
            URI uri = new URI(url);
            
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
                throw new InvalidUrlException("Only http and https schemes are allowed");
            }

            String host = uri.getHost();
            if (host == null || host.trim().isEmpty()) {
                throw new InvalidUrlException("URL must have a valid host");
            }

            if (BLOCKED_HOSTS.contains(host.toLowerCase())) {
                throw new InvalidUrlException("Access to metadata endpoints is blocked");
            }

            if (allowedHosts != null && allowedHosts.contains(host)) {
                return;
            }

            InetAddress[] addresses = resolveHost(host);
            
            for (InetAddress address : addresses) {
                if (!allowPrivateIps && isPrivateOrLocalAddress(address)) {
                    throw new InvalidUrlException("Access to private IP addresses is not allowed: " + address.getHostAddress());
                }
            }

        } catch (InvalidUrlException e) {
            throw e;
        } catch (UnknownHostException e) {
            // Its own type, because not resolving is not a verdict: a resolver timeout or a
            // record mid-change says nothing about where the name points. A caller configuring a
            // URL may still refuse it; a caller delivering to one has to be able to try again.
            throw new UnresolvableHostException("Cannot resolve host: " + e.getMessage());
        } catch (Exception e) {
            throw new InvalidUrlException("Invalid URL: " + e.getMessage());
        }
    }

    /**
     * The admission check's decision, made again against an address that is already connected.
     *
     * <p>{@link #validateWebhookUrl} answers this question at DNS time; the post-connect
     * customizer has to answer the same one about the socket's real peer, which is what closes
     * the DNS rebinding window. Both must reach the same verdict from the same inputs, or an
     * operator's configuration is honoured by one half and silently ignored by the other — as
     * happened with {@code allowedHosts}, where an allow-listed internal host passed admission
     * and then had every connection to it torn down.
     *
     * @param host        the host as it was written, not as it resolved — the allow list is a
     *                    list of names, and matching a resolved literal against it would let a
     *                    rebinding answer inherit an entry meant for something else
     * @param address     the address the connection actually reached
     */
    public static boolean isBlockedTarget(String host, InetAddress address,
                                          boolean allowPrivateIps, List<String> allowedHosts) {
        if (host != null && BLOCKED_HOSTS.contains(host.toLowerCase())) {
            return true;
        }
        if (allowPrivateIps) {
            return false;
        }
        if (host != null && allowedHosts != null && allowedHosts.contains(host)) {
            return false;
        }
        return isPrivateOrLocalAddress(address);
    }

    public static boolean isPrivateOrLocalAddress(InetAddress address) {
        // 0.0.0.0 and :: are not "nowhere": a connect() to either reaches the local host.
        if (address.isAnyLocalAddress()) {
            return true;
        }

        if (address.isLoopbackAddress()) {
            return true;
        }

        if (address.isLinkLocalAddress()) {
            return true;
        }

        if (address.isSiteLocalAddress()) {
            return true;
        }

        if (address.isMulticastAddress()) {
            return true;
        }

        byte[] addr = address.getAddress();

        if (addr.length == 4) {
            return isPrivateIPv4(addr);
        } else if (addr.length == 16) {
            return isPrivateIPv6(addr);
        }

        return false;
    }

    private static boolean isPrivateIPv4(byte[] addr) {
        int firstOctet = addr[0] & 0xFF;
        int secondOctet = addr[1] & 0xFF;
        int thirdOctet = addr[2] & 0xFF;

        if (firstOctet == 10) {
            return true;
        }

        if (firstOctet == 172 && secondOctet >= 16 && secondOctet <= 31) {
            return true;
        }

        if (firstOctet == 192 && secondOctet == 168) {
            return true;
        }

        if (firstOctet == 169 && secondOctet == 254) {
            return true;
        }

        if (firstOctet == 127) {
            return true;
        }

        if (firstOctet == 0) {
            return true;
        }

        // 100.64.0.0/10 - Carrier-Grade NAT (RFC 6598). In-cluster pod/service traffic
        // on EKS/GKE frequently lives here, and Alibaba Cloud's metadata service
        // (100.100.100.200, also hard-blocked via BLOCKED_HOSTS) sits inside this range.
        if (firstOctet == 100 && secondOctet >= 64 && secondOctet <= 127) {
            return true;
        }

        // 192.0.0.0/24 - IETF Protocol Assignments (RFC 6890): includes the DS-Lite
        // AFTR address (192.0.0.1) and other special-purpose addressing that should
        // never be a legitimate public webhook target.
        if (firstOctet == 192 && secondOctet == 0 && thirdOctet == 0) {
            return true;
        }

        // 198.18.0.0/15 - benchmarking address space (RFC 2544): routable-looking but
        // reserved for network testing, not meant to be reachable in production.
        if (firstOctet == 198 && (secondOctet == 18 || secondOctet == 19)) {
            return true;
        }

        // 224.0.0.0/4 - multicast.
        if (firstOctet >= 224 && firstOctet <= 239) {
            return true;
        }

        // 240.0.0.0/4 - reserved for future use, including the 255.255.255.255
        // broadcast address.
        if (firstOctet >= 240) {
            return true;
        }

        return false;
    }

    private static boolean isPrivateIPv6(byte[] addr) {
        // fe80::/10 - link-local.
        if (addr[0] == (byte) 0xfe && (addr[1] & 0xC0) == 0x80) {
            return true;
        }

        // fc00::/7 - unique local.
        if ((addr[0] & 0xfe) == 0xfc) {
            return true;
        }

        // ff00::/8 - multicast, refused for the same reason 224.0.0.0/4 is.
        if (addr[0] == (byte) 0xff) {
            return true;
        }

        // ::/96 - the unspecified address, loopback, and the deprecated IPv4-compatible form.
        // None of it is a public target, so it is refused whole rather than decoded.
        if (allZero(addr, 0, 12)) {
            return true;
        }

        // The ranges below carry an IPv4 address inside them, and whatever translates them
        // (the host stack, a NAT64 gateway, a 6to4 relay) delivers to that address. Judging them
        // as IPv6 would let a literal like 64:ff9b::a9fe:a9fe reach the metadata service.

        // ::ffff:0:0/96 - IPv4-mapped. Java folds most of these into Inet4Address on parse, but
        // an address taken off a socket or built from bytes can still arrive in this form.
        if (allZero(addr, 0, 10) && addr[10] == (byte) 0xff && addr[11] == (byte) 0xff) {
            return isPrivateIPv4(Arrays.copyOfRange(addr, 12, 16));
        }

        // 64:ff9b::/96 - well-known NAT64 prefix.
        if (addr[0] == 0x00 && addr[1] == 0x64 && addr[2] == (byte) 0xff && addr[3] == (byte) 0x9b) {
            if (allZero(addr, 4, 12)) {
                return isPrivateIPv4(Arrays.copyOfRange(addr, 12, 16));
            }
            // 64:ff9b:1::/48 - local-use NAT64: translates into whatever the operator's network
            // routes, which is exactly what this guard exists to keep out of reach.
            if (addr[4] == 0x00 && addr[5] == 0x01) {
                return true;
            }
        }

        // 2002::/16 - 6to4, the IPv4 address in the next 32 bits.
        if (addr[0] == 0x20 && addr[1] == 0x02) {
            return isPrivateIPv4(Arrays.copyOfRange(addr, 2, 6));
        }

        return false;
    }

    private static boolean allZero(byte[] addr, int from, int to) {
        for (int i = from; i < to; i++) {
            if (addr[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress[] resolveHost(String host) throws UnknownHostException {
        InetAddress[] cached = DNS_CACHE.getIfPresent(host);
        if (cached != null) {
            return cached;
        }

        InetAddress[] addresses = InetAddress.getAllByName(host);
        DNS_CACHE.put(host, addresses);
        return addresses;
    }

    public static class InvalidUrlException extends RuntimeException {
        public InvalidUrlException(String message) {
            super(message);
        }
    }

    public static class UnresolvableHostException extends InvalidUrlException {
        public UnresolvableHostException(String message) {
            super(message);
        }
    }
}
