package com.webhook.platform.api.mcp;

import com.webhook.platform.api.security.ApiKeyAuthenticationToken;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * The transport behind {@code /mcp}, replacing the one Spring AI would build for one reason: its
 * default hands every tool an empty {@link McpTransportContext}.
 *
 * <p>A tool call is not a handler method, so nothing a controller relies on reaches it — not the
 * {@code AuthContext} argument resolver, not {@code ScopeEnforcementInterceptor}, and not a
 * guarantee about which thread it runs on. What does reach it is the transport context, built
 * here on the request thread while the security chain's identity is still in place. The tools
 * take the caller from there and enter its tenant themselves; see {@link McpCaller}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfig {

    /** Where the server answers. Fixed rather than configurable: clients are given this URL. */
    public static final String ENDPOINT = "/mcp";

    /** The transport-context key the caller's API-key authentication travels under. */
    static final String CALLER = "railhook.caller";

    @Bean
    public WebMvcStatelessServerTransport webMvcStatelessServerTransport(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .messageEndpoint(ENDPOINT)
                .contextExtractor(request -> {
                    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                    if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
                        return McpTransportContext.create(Map.of(CALLER, apiKey));
                    }
                    return McpTransportContext.EMPTY;
                })
                .build();
    }
}
