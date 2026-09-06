package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.exception.UnauthorizedException;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class AuthContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthContext.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth instanceof JwtAuthenticationToken jwt) {
            return new AuthContext(
                    jwt.getUserId(),
                    jwt.getOrganizationId(),
                    jwt.getRole(),
                    null,
                    null
            );
        }

        if (auth instanceof ApiKeyAuthenticationToken apiKey) {
            // The organization was resolved during authentication, where the tenant-less lookup
            // belongs. A key that reaches here always has one: ApiKeyAuthenticationFilter leaves
            // the request unauthenticated when the project is missing.
            return new AuthContext(
                    null,
                    apiKey.getOrganizationId(),
                    MembershipRole.API_KEY,
                    apiKey.getProjectId(),
                    apiKey.getScope()
            );
        }

        throw new UnauthorizedException("Authentication required");
    }
}
