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
        // Rate limit first, so a flood is turned away cheaply. The portal is excluded: each
        // portal session has its own budget, so a Consumer cannot use up the organization's.
        registry.addInterceptor(organizationRateLimitInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/portal/**");
        registry.addInterceptor(scopeEnforcementInterceptor)
                .addPathPatterns("/api/**");
    }
}
