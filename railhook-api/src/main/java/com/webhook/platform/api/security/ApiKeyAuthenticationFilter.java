package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.entity.ApiKey;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.ApiKeyRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.mcp.McpServerConfig;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.util.CryptoUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.slf4j.MDC;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String BEARER_PREFIX = "Bearer ";
    private final ApiKeyRepository apiKeyRepository;
    private final ProjectRepository projectRepository;

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeyRepository, ProjectRepository projectRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.projectRepository = projectRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String apiKeyValue = apiKeyOf(request);

        if (apiKeyValue != null && !apiKeyValue.isEmpty()) {
            String keyHash = CryptoUtils.hashApiKey(apiKeyValue);

            // System scope: these reads are how the tenant is found in the first place.
            Optional<ApiKey> apiKeyOpt = TenantContext.callAsSystem(() -> apiKeyRepository.findByKeyHash(keyHash));

            if (apiKeyOpt.isPresent()) {
                ApiKey apiKey = apiKeyOpt.get();
                
                if (apiKey.getRevokedAt() == null && 
                    (apiKey.getExpiresAt() == null || apiKey.getExpiresAt().isAfter(Instant.now()))) {

                    Optional<Project> project = TenantContext.callAsSystem(
                            () -> projectRepository.findById(apiKey.getProjectId()));

                    if (project.isPresent()) {
                        ApiKeyAuthenticationToken authentication = new ApiKeyAuthenticationToken(
                                apiKeyValue,
                                apiKey.getProjectId(),
                                project.get().getOrganizationId(),
                                apiKey.getScope(),
                                Collections.emptyList()
                        );
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        MDC.put("projectId", apiKey.getProjectId().toString());
                    }
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Accepts {@code Authorization: Bearer} only on the MCP endpoint, because many MCP clients
     * can send nothing else. Everywhere else a bearer token is a JWT.
     */
    private static String apiKeyOf(HttpServletRequest request) {
        String header = request.getHeader(API_KEY_HEADER);
        if (header != null && !header.isEmpty()) {
            return header;
        }
        if (!McpServerConfig.ENDPOINT.equals(request.getRequestURI())) {
            return null;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
