package com.webhook.platform.api.mcp;

import com.webhook.platform.api.security.ApiKeyAuthenticationToken;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Replaces Spring AI's default transport, which hands every tool an empty transport context. A
 * tool call gets neither the AuthContext resolver nor ScopeEnforcementInterceptor, and may run on
 * another thread, so the caller is captured here on the request thread and the tools enter its
 * tenant themselves.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfig {

    // Not configurable: clients are given this URL.
    public static final String ENDPOINT = "/mcp";

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

    // Protocol errors are answered as JSON-RPC rather than HTTP 500.
    @Bean
    @Primary
    public McpStatelessServerTransport mcpStatelessServerTransport(WebMvcStatelessServerTransport transport) {
        return new JsonRpcErrorAnsweringTransport(transport);
    }
}
