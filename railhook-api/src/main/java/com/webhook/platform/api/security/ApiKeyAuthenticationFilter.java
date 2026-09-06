package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.entity.ApiKey;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.ApiKeyRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
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
    private final ApiKeyRepository apiKeyRepository;
    private final ProjectRepository projectRepository;

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeyRepository, ProjectRepository projectRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.projectRepository = projectRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String apiKeyValue = request.getHeader(API_KEY_HEADER);

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
}
