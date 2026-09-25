package com.webhook.platform.api.mcp.oauth;

import com.webhook.platform.api.mcp.McpServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The issuer is {@code app.base-url}, never the request's Host header: an issuer an attacker can
 * pick by sending a different Host is the mix-up attack RFC 9207 exists to stop.
 */
@Component
public class McpOAuthSettings {

    public static final String SCOPE_READ = "mcp:read";
    public static final String SCOPE_WRITE = "mcp:write";
    public static final List<String> SCOPES = List.of(SCOPE_READ, SCOPE_WRITE);

    public static final Duration REQUEST_LIFETIME = Duration.ofMinutes(10);
    public static final Duration CODE_LIFETIME = Duration.ofSeconds(60);
    public static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofHours(1);
    // Sliding: each refresh issues a new one.
    public static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(30);

    private final boolean enabled;
    private final String issuer;

    public McpOAuthSettings(
            @Value("${spring.ai.mcp.server.enabled:true}") boolean mcpEnabled,
            @Value("${mcp.oauth.enabled:true}") boolean oauthEnabled,
            @Value("${app.base-url:http://localhost:5173}") String baseUrl) {
        this.enabled = mcpEnabled && oauthEnabled;
        this.issuer = stripTrailingSlash(baseUrl.trim());
    }

    public boolean enabled() {
        return enabled;
    }

    public String issuer() {
        return issuer;
    }

    public String resource() {
        return issuer + McpServerConfig.ENDPOINT;
    }

    // RFC 9728 3.1: the well-known segment goes between the origin and the resource path.
    public String resourceMetadataUrl() {
        return issuer + "/.well-known/oauth-protected-resource" + McpServerConfig.ENDPOINT;
    }

    public String authorizationEndpoint() {
        return issuer + "/oauth/authorize";
    }

    public String tokenEndpoint() {
        return issuer + "/oauth/token";
    }

    public String registrationEndpoint() {
        return issuer + "/oauth/register";
    }

    public String revocationEndpoint() {
        return issuer + "/oauth/revoke";
    }

    public String consentPage() {
        return issuer + "/oauth/consent";
    }

    public String documentation() {
        return issuer + "/docs/tools/mcp/";
    }

    // Both the endpoint and the bare origin are accepted, since RFC 9728 publishes metadata for each.
    public boolean isThisResource(String candidate) {
        String normalized = normalize(candidate);
        return normalized != null && (normalized.equals(normalize(resource())) || normalized.equals(normalize(issuer)));
    }

    private static String normalize(String uri) {
        try {
            URI parsed = new URI(uri.trim());
            if (parsed.getScheme() == null || parsed.getHost() == null || parsed.getFragment() != null) {
                return null;
            }
            String path = parsed.getRawPath() == null ? "" : stripTrailingSlash(parsed.getRawPath());
            int port = parsed.getPort();
            return parsed.getScheme().toLowerCase(Locale.ROOT) + "://" + parsed.getHost().toLowerCase(Locale.ROOT)
                    + (port == -1 ? "" : ":" + port) + path;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripTrailingSlash(String value) {
        String out = value;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
