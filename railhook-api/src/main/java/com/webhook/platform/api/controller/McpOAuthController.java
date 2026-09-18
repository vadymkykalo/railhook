package com.webhook.platform.api.controller;

import com.webhook.platform.api.mcp.oauth.McpOAuthSettings;
import com.webhook.platform.api.mcp.oauth.OAuthProtocolException;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.AuthRateLimiterService;
import com.webhook.platform.api.service.McpOAuthService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The OAuth 2.1 endpoints an MCP client talks to on its own: discovery metadata, dynamic client
 * registration, the authorization redirect, the token endpoint and revocation.
 *
 * <p>Hidden from the OpenAPI document: these are defined by RFCs 6749, 7009, 7591, 8414 and 9728,
 * which describe them better than a generated schema could, and nothing but an OAuth client calls
 * them. The screens a person uses — consent and the list of connected apps — are ordinary API
 * endpoints in {@link McpConsentController} and {@link McpGrantController}.
 *
 * <p>Errors come back in the OAuth shape, {@code {"error", "error_description"}}, never the
 * dashboard's, because OAuth clients branch on the {@code error} code.
 */
@Hidden
@RestController
public class McpOAuthController {

    private final McpOAuthService oauthService;
    private final McpOAuthSettings settings;
    private final AuthRateLimiterService rateLimiter;
    private final TrustedProxyResolver trustedProxyResolver;

    public McpOAuthController(McpOAuthService oauthService, McpOAuthSettings settings,
                              AuthRateLimiterService rateLimiter, TrustedProxyResolver trustedProxyResolver) {
        this.oauthService = oauthService;
        this.settings = settings;
        this.rateLimiter = rateLimiter;
        this.trustedProxyResolver = trustedProxyResolver;
    }

    /**
     * Protected resource metadata (RFC 9728) for the MCP endpoint. The path-suffixed address is the
     * one the 401 challenge names; the root one describes the origin, since RFC 9728 §3.3 binds a
     * document's {@code resource} to the URL it was fetched from.
     */
    @GetMapping("/.well-known/oauth-protected-resource/mcp")
    public ResponseEntity<Map<String, Object>> protectedResourceMetadata() {
        return metadata(protectedResource(settings.resource()));
    }

    @GetMapping("/.well-known/oauth-protected-resource")
    public ResponseEntity<Map<String, Object>> protectedResourceMetadataAtRoot() {
        return metadata(protectedResource(settings.issuer()));
    }

    /** Authorization server metadata (RFC 8414). */
    @GetMapping("/.well-known/oauth-authorization-server")
    public ResponseEntity<Map<String, Object>> authorizationServerMetadata() {
        requireEnabled();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", settings.issuer());
        body.put("authorization_endpoint", settings.authorizationEndpoint());
        body.put("token_endpoint", settings.tokenEndpoint());
        body.put("registration_endpoint", settings.registrationEndpoint());
        body.put("revocation_endpoint", settings.revocationEndpoint());
        body.put("response_types_supported", List.of("code"));
        body.put("response_modes_supported", List.of("query"));
        body.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        body.put("code_challenge_methods_supported", List.of("S256"));
        List<String> authMethods = List.of("none", "client_secret_post", "client_secret_basic");
        body.put("token_endpoint_auth_methods_supported", authMethods);
        body.put("revocation_endpoint_auth_methods_supported", authMethods);
        body.put("scopes_supported", McpOAuthSettings.SCOPES);
        body.put("authorization_response_iss_parameter_supported", true);
        body.put("service_documentation", settings.documentation());
        return metadata(body);
    }

    /** Dynamic client registration (RFC 7591). Open, as MCP clients need it to be; bounded per address. */
    @PostMapping(value = "/oauth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> registerOAuthClient(@RequestBody Map<String, Object> metadata,
                                                        HttpServletRequest request) {
        requireEnabled();
        if (!rateLimiter.allowOAuthRegister(trustedProxyResolver.resolve(request))) {
            throw new OAuthProtocolException("slow_down", "Too many registrations. Try again later.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(oauthService.registerClient(metadata));
    }

    /**
     * Where the app sends the person's browser. Always a redirect: to the dashboard's consent
     * screen, or back to the app with an error once its redirect URI is known to be its own.
     */
    @GetMapping("/oauth/authorize")
    public ResponseEntity<Void> authorizeOAuthRequest(@RequestParam Map<String, String> params) {
        requireEnabled();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauthService.authorize(params)))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> issueOAuthToken(@RequestParam MultiValueMap<String, String> form,
                                                     @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                     HttpServletRequest request) {
        requireEnabled();
        Map<String, String> params = single(form);
        if (!rateLimiter.allowOAuthToken(trustedProxyResolver.resolve(request), params.get("client_id"))) {
            throw new OAuthProtocolException("slow_down", "Too many token requests. Try again later.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(oauthService.token(params, authorization));
    }

    /** Token revocation (RFC 7009). Answers 200 whether or not the token existed. */
    @PostMapping(value = "/oauth/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> revokeOAuthToken(@RequestParam MultiValueMap<String, String> form,
                                       @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                       HttpServletRequest request) {
        requireEnabled();
        Map<String, String> params = single(form);
        if (!rateLimiter.allowOAuthToken(trustedProxyResolver.resolve(request), params.get("client_id"))) {
            throw new OAuthProtocolException("slow_down", "Too many requests. Try again later.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
        oauthService.revokeToken(params, authorization);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(OAuthProtocolException.class)
    public ResponseEntity<Map<String, String>> oauthError(OAuthProtocolException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore());
        if (e.status() == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"railhook\"");
        }
        return response.body(Map.of("error", e.error(), "error_description", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("error", "invalid_request", "error_description", "The request body is not valid JSON"));
    }

    private Map<String, Object> protectedResource(String resource) {
        requireEnabled();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", resource);
        body.put("authorization_servers", List.of(settings.issuer()));
        body.put("scopes_supported", McpOAuthSettings.SCOPES);
        body.put("bearer_methods_supported", List.of("header"));
        body.put("resource_name", "Railhook");
        body.put("resource_documentation", settings.documentation());
        return body;
    }

    private static ResponseEntity<Map<String, Object>> metadata(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(body);
    }

    /**
     * A repeated parameter is an error in OAuth (RFC 6749 §3.1), not a list: which copy would win
     * is exactly the ambiguity a parameter-pollution attack needs.
     */
    private static Map<String, String> single(MultiValueMap<String, String> form) {
        Map<String, String> out = new LinkedHashMap<>();
        form.forEach((name, values) -> {
            if (values.size() > 1) {
                throw OAuthProtocolException.invalidRequest(name + " is repeated");
            }
            out.put(name, values.get(0));
        });
        return out;
    }

    private void requireEnabled() {
        if (!settings.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
