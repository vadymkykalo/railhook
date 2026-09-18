package com.webhook.platform.api.config;

import com.webhook.platform.api.security.AuthContextArgumentResolver;
import com.webhook.platform.api.security.PortalContextArgumentResolver;
import com.webhook.platform.api.security.OrganizationRateLimitInterceptor;
import com.webhook.platform.api.security.ScopeEnforcementInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthContextArgumentResolver authContextArgumentResolver;
    private final PortalContextArgumentResolver portalContextArgumentResolver;
    private final ScopeEnforcementInterceptor scopeEnforcementInterceptor;
    private final OrganizationRateLimitInterceptor organizationRateLimitInterceptor;

    public WebConfig(AuthContextArgumentResolver authContextArgumentResolver,
                     PortalContextArgumentResolver portalContextArgumentResolver,
                     ScopeEnforcementInterceptor scopeEnforcementInterceptor,
                     OrganizationRateLimitInterceptor organizationRateLimitInterceptor) {
        this.authContextArgumentResolver = authContextArgumentResolver;
        this.portalContextArgumentResolver = portalContextArgumentResolver;
        this.scopeEnforcementInterceptor = scopeEnforcementInterceptor;
        this.organizationRateLimitInterceptor = organizationRateLimitInterceptor;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(authContextArgumentResolver);
        resolvers.add(portalContextArgumentResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // The rate limit runs first: there is no point authorising a request that is about to
        // be refused anyway, and a caller flooding the API should be turned away as cheaply as
        // possible.
        // Not the portal: each portal session has a budget of its own, spent in
        // PortalSessionAuthenticationFilter, so a Consumer's browser cannot use up the one the
        // customer's own dashboard and backend share.
        registry.addInterceptor(organizationRateLimitInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/portal/**");
        registry.addInterceptor(scopeEnforcementInterceptor)
                .addPathPatterns("/api/**");
    }
}
