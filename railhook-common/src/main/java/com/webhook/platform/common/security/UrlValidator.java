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
 * SSRF guard for outbound URLs. A denylist of special-purpose ranges rather than an allowlist of
 * routable space: an allowlist would have to follow IANA as reserved blocks become routable, and
 * a false rejection on the delivery path is costly. Internal targets are opted into with
 * WEBHOOK_ALLOW_PRIVATE_IPS or the per-endpoint allowed hosts.
 */
public class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    
    // Blocked even for allowed hosts: this check runs before the allow-list bypass.
    private static final List<String> BLOCKED_HOSTS = List.of(
            "metadata.google.internal",
            // AWS, Azure and OCI metadata.
            "169.254.169.254",
            // Alibaba metadata. Also inside CGNAT, but listed so allowing private IPs does not open it.
            "100.100.100.200"
    );

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
            // Not resolving is not a verdict (resolver timeout, record mid-change). A caller
            // delivering to the URL must be able to retry.
            throw new UnresolvableHostException("Cannot resolve host: " + e.getMessage());
        } catch (Exception e) {
            throw new InvalidUrlException("Invalid URL: " + e.getMessage());
        }
    }

    /**
     * The same decision as {@link #validateWebhookUrl}, made against the connected peer to close the
     * DNS rebinding window. The two must agree: they once disagreed on {@code allowedHosts} and
     * every connection to an allow-listed host was torn down.
     *
     * @param host the host as written, not as resolved, so a rebinding answer cannot inherit an
     *             allow-list entry meant for another name
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

        // 100.64.0.0/10 CGNAT: in-cluster traffic on EKS/GKE often lives here.
        if (firstOctet == 100 && secondOctet >= 64 && secondOctet <= 127) {
            return true;
        }

        // 192.0.0.0/24 IETF protocol assignments.
        if (firstOctet == 192 && secondOctet == 0 && thirdOctet == 0) {
            return true;
        }

        // 198.18.0.0/15 benchmarking.
        if (firstOctet == 198 && (secondOctet == 18 || secondOctet == 19)) {
            return true;
        }

        if (firstOctet >= 224 && firstOctet <= 239) {
            return true;
        }

        // 240.0.0.0/4 reserved, including broadcast.
        if (firstOctet >= 240) {
            return true;
        }

        return false;
    }

    private static boolean isPrivateIPv6(byte[] addr) {
        if (addr[0] == (byte) 0xfe && (addr[1] & 0xC0) == 0x80) {
            return true;
        }

        if ((addr[0] & 0xfe) == 0xfc) {
            return true;
        }

        if (addr[0] == (byte) 0xff) {
            return true;
        }

        // ::/96: unspecified, loopback and the deprecated IPv4-compatible form.
        if (allZero(addr, 0, 12)) {
            return true;
        }

        // The ranges below embed an IPv4 address and are delivered to it, so judge that address.
        // Otherwise 64:ff9b::a9fe:a9fe would reach the metadata service.

        // IPv4-mapped. Java usually folds these into Inet4Address, but not from raw bytes.
        if (allZero(addr, 0, 10) && addr[10] == (byte) 0xff && addr[11] == (byte) 0xff) {
            return isPrivateIPv4(Arrays.copyOfRange(addr, 12, 16));
        }

        // 64:ff9b::/96 NAT64.
        if (addr[0] == 0x00 && addr[1] == 0x64 && addr[2] == (byte) 0xff && addr[3] == (byte) 0x9b) {
            if (allZero(addr, 4, 12)) {
                return isPrivateIPv4(Arrays.copyOfRange(addr, 12, 16));
            }
            // 64:ff9b:1::/48 local-use NAT64 routes into the operator's network.
            if (addr[4] == 0x00 && addr[5] == 0x01) {
                return true;
            }
        }

        // 2002::/16 6to4.
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
