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

            // Authentication precedes tenancy: `api_keys` and `projects` are both tenant-scoped,
            // and the tenant is what these two reads are for. System scope is the only honest
            // answer here -- the alternative is a chicken-and-egg where the key cannot be looked
            // up until the organization it names is already known.
            Optional<ApiKey> apiKeyOpt = TenantContext.callAsSystem(() -> apiKeyRepository.findByKeyHash(keyHash));

            if (apiKeyOpt.isPresent()) {
                ApiKey apiKey = apiKeyOpt.get();
                
                if (apiKey.getRevokedAt() == null && 
                    (apiKey.getExpiresAt() == null || apiKey.getExpiresAt().isAfter(Instant.now()))) {

                    Optional<Project> project = TenantContext.callAsSystem(
                            () -> projectRepository.findById(apiKey.getProjectId()));

                    // A key whose project is gone authenticates nothing. Previously this surfaced
                    // later, as an UnauthorizedException from AuthContextArgumentResolver; leaving
                    // the request unauthenticated here reaches the same 401 without a tenant-less
                    // authenticated token existing in between.
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
     * The key from {@code X-API-Key}, or — on the MCP endpoint only — from
     * {@code Authorization: Bearer}, which is the one header many MCP clients know how to send.
     *
     * <p>Confined to that path so the rest of the API keeps a single way in for a key: a bearer
     * token everywhere else is a JWT, and {@link JwtAuthenticationFilter} ignores a bearer value
     * it cannot parse, so the two never claim the same request.
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
