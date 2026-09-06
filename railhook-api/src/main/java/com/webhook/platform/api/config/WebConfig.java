package com.webhook.platform.api.config;

import com.webhook.platform.api.security.AuthContextArgumentResolver;
import com.webhook.platform.api.security.ScopeEnforcementInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthContextArgumentResolver authContextArgumentResolver;
    private final ScopeEnforcementInterceptor scopeEnforcementInterceptor;

    public WebConfig(AuthContextArgumentResolver authContextArgumentResolver,
                     ScopeEnforcementInterceptor scopeEnforcementInterceptor) {
        this.authContextArgumentResolver = authContextArgumentResolver;
        this.scopeEnforcementInterceptor = scopeEnforcementInterceptor;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(authContextArgumentResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(scopeEnforcementInterceptor)
                .addPathPatterns("/api/**");
    }
}
