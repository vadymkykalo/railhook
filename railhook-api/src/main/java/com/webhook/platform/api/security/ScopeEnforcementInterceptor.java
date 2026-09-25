package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.exception.DemoReadOnlyException;
import com.webhook.platform.api.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Project confinement for API keys is structural: every route whose URI template carries
 * {@code {projectId}} is compared against the key's own project, whatever the handler does,
 * unless it declares {@link ProjectScopeExempt}. An organization check alone would pass for any
 * project in the key's organization.
 */
@Slf4j
@Component
public class ScopeEnforcementInterceptor implements HandlerInterceptor {

    private static final String PROJECT_ID_PATH_VAR = "projectId";

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final SuspensionCheck suspensionCheck;

    public ScopeEnforcementInterceptor(SuspensionCheck suspensionCheck) {
        this.suspensionCheck = suspensionCheck;
    }

    /**
     * Runs before the scope check because that one returns early for a JWT, and dashboard
     * callers are the ones a VIEWER role applies to.
     */
    void enforceAccessLevel(HandlerMethod handlerMethod, Authentication authentication) {
        if (DemoSessions.isDemo(authentication) && handlerMethod.hasMethodAnnotation(AllowedInDemo.class)) {
            // The demo is always a VIEWER, so without this an AllowedInDemo handler above READ
            // would be unreachable. Only a method-level annotation counts, and
            // DemoSessionAllowListTest freezes the set.
            return;
        }
        RequireAccess required = handlerMethod.getMethodAnnotation(RequireAccess.class);
        if (required == null) {
            required = handlerMethod.getBeanType().getAnnotation(RequireAccess.class);
        }
        if (required == null || required.value() == AccessLevel.READ) {
            return;
        }

        MembershipRole role;
        ApiKeyScope scope = null;
        if (authentication instanceof JwtAuthenticationToken jwt) {
            role = jwt.getRole();
        } else if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
            role = MembershipRole.API_KEY;
            scope = apiKey.getScope();
        } else {
            log.warn("Access level denied: {} declares {} but the caller carries no membership role ({})",
                    handlerMethod.getMethod().getName(), required.value(),
                    authentication == null ? "unauthenticated" : authentication.getClass().getSimpleName());
            throw new ForbiddenException(
                    "This endpoint requires a membership role the caller does not have. A handler that "
                            + "declares an access level is a tenant endpoint; platform-admin credentials "
                            + "belong on /api/v1/admin/**.");
        }

        // Same RbacUtil the handlers call, so there is one definition of write access.
        if (required.value() == AccessLevel.OWNER) {
            RbacUtil.requireOwnerAccess(role);
        } else {
            RbacUtil.requireWriteAccess(role, scope);
        }
    }

    /**
     * Refuses writes from an unverified account. With mail disabled, registration marks accounts
     * verified immediately, so this is inert there.
     *
     * <p>API keys are not re-checked: creating a key is itself a write that passed this gate,
     * and checking again would add a user lookup to every ingest request.
     */
    void enforceVerifiedEmail(HandlerMethod handlerMethod, Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || jwt.isEmailVerified()) {
            return;
        }

        RequireAccess required = handlerMethod.getMethodAnnotation(RequireAccess.class);
        if (required == null) {
            required = handlerMethod.getBeanType().getAnnotation(RequireAccess.class);
        }
        if (required == null || required.value() == AccessLevel.READ) {
            return;
        }

        log.warn("Write refused: user {} has not verified its email address ({}.{})",
                jwt.getUserId(), handlerMethod.getBeanType().getSimpleName(),
                handlerMethod.getMethod().getName());
        throw new ForbiddenException(
                "Verify your email address before making changes. A new verification link can be "
                        + "requested from the dashboard.");
    }

    /**
     * Keyed off the HTTP method rather than {@link RequireAccess} because ingest carries no
     * access level and is what a suspension most needs to stop. The operator's reason is
     * returned to the caller on purpose.
     */
    void enforceNotSuspended(HttpServletRequest request, Authentication authentication) {
        if (READ_METHODS.contains(request.getMethod())) {
            return;
        }

        UUID organizationId;
        if (authentication instanceof PlatformAdminUserAuthenticationToken) {
            // Their own organization's suspension must not stop them lifting someone else's.
            return;
        } else if (authentication instanceof JwtAuthenticationToken jwt) {
            organizationId = jwt.getOrganizationId();
        } else if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
            organizationId = apiKey.getOrganizationId();
        } else if (authentication instanceof PortalSessionAuthenticationToken portal) {
            organizationId = portal.getOrganizationId();
        } else {
            return;
        }

        suspensionCheck.suspensionReason(organizationId).ifPresent(reason -> {
            log.warn("Write refused: organization {} is suspended ({})", organizationId, reason);
            throw new ForbiddenException("This organization is suspended and cannot make changes."
                    + (reason.isBlank() ? "" : " Reason: " + reason));
        });
    }

    /**
     * Keyed off the HTTP method rather than {@link RequireAccess}: the handlers without an
     * access level (password, members, CLI and MCP approval, portal) are the ones a stranger
     * holding the demo token should reach least. Runs first so a demo caller learns nothing from
     * the other gates.
     */
    void enforceDemoReadOnly(HttpServletRequest request, Object handler, Authentication authentication) {
        if (!DemoSessions.isDemo(authentication)) {
            return;
        }
        HandlerMethod handlerMethod = handler instanceof HandlerMethod hm ? hm : null;
        if (READ_METHODS.contains(request.getMethod())) {
            if (handlerMethod != null && handlerMethod.hasMethodAnnotation(RefusedInDemo.class)) {
                throw new DemoReadOnlyException();
            }
            return;
        }
        if (handlerMethod != null && handlerMethod.hasMethodAnnotation(AllowedInDemo.class)) {
            return;
        }
        log.debug("Demo session refused: {} {}", request.getMethod(), request.getRequestURI());
        throw new DemoReadOnlyException();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        enforceDemoReadOnly(request, handler, authentication);

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        enforceProjectScope(request, handlerMethod, authentication);
        enforceAccessLevel(handlerMethod, authentication);
        enforceVerifiedEmail(handlerMethod, authentication);
        enforceNotSuspended(request, authentication);

        if (!(authentication instanceof ApiKeyAuthenticationToken apiKeyAuth)) {
            return true;
        }

        RequireScope methodAnnotation = handlerMethod.getMethodAnnotation(RequireScope.class);
        RequireScope classAnnotation = handlerMethod.getBeanType().getAnnotation(RequireScope.class);

        RequireScope effective = methodAnnotation != null ? methodAnnotation : classAnnotation;
        if (effective == null) {
            return true;
        }

        ApiKeyScope required = effective.value();
        ApiKeyScope actual = apiKeyAuth.getScope();

        if (required == ApiKeyScope.READ_WRITE && actual == ApiKeyScope.READ_ONLY) {
            log.warn("API key scope denied: required={}, actual={}, method={}.{}, projectId={}",
                    required, actual,
                    handlerMethod.getBeanType().getSimpleName(),
                    handlerMethod.getMethod().getName(),
                    apiKeyAuth.getProjectId());
            throw new ForbiddenException("API key scope insufficient. Required: " + required + ", actual: " + actual);
        }

        return true;
    }

    private void enforceProjectScope(HttpServletRequest request, HandlerMethod handlerMethod,
                                      Authentication authentication) {
        if (!(authentication instanceof ApiKeyAuthenticationToken apiKeyAuth)) {
            // Only API keys are confined to one project; users are scoped by membership.
            return;
        }

        if (isProjectScopeExempt(handlerMethod)) {
            return;
        }

        Object templateVars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(templateVars instanceof Map<?, ?> vars)) {
            return;
        }

        Object rawProjectId = vars.get(PROJECT_ID_PATH_VAR);
        if (rawProjectId == null) {
            return;
        }

        UUID pathProjectId;
        try {
            pathProjectId = UUID.fromString(rawProjectId.toString());
        } catch (IllegalArgumentException e) {
            log.warn("ScopeEnforcementInterceptor: {} path variable '{}' on {}.{} is not a UUID",
                    PROJECT_ID_PATH_VAR, rawProjectId,
                    handlerMethod.getBeanType().getSimpleName(), handlerMethod.getMethod().getName());
            throw new ForbiddenException("Invalid project identifier");
        }

        if (!pathProjectId.equals(apiKeyAuth.getProjectId())) {
            log.warn("Project scope violation: API key for project {} attempted {} {} (project {}) via {}.{}",
                    apiKeyAuth.getProjectId(), request.getMethod(), request.getRequestURI(), pathProjectId,
                    handlerMethod.getBeanType().getSimpleName(), handlerMethod.getMethod().getName());
            throw new ForbiddenException("API key does not have access to this project");
        }
    }

    private boolean isProjectScopeExempt(HandlerMethod handlerMethod) {
        return handlerMethod.getMethodAnnotation(ProjectScopeExempt.class) != null
                || handlerMethod.getBeanType().getAnnotation(ProjectScopeExempt.class) != null;
    }
}
