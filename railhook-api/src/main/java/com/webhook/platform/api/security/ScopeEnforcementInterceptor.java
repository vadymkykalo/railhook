package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.MembershipRole;
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
 * Enforces three things before a handler runs: the {@link RequireAccess} level, the
 * {@link RequireScope} annotation for API-key requests, and the structural
 * {@code {projectId}} tenancy guard described below.
 *
 * <p>{@link RequireScope} resolves method-level first, then class-level; neither means an API-key
 * request is allowed, and a JWT skips this half entirely.
 *
 * <p>Project confinement is structural. {@code AuthContext.organizationId} comes off the key's
 * project, so an organization-only check passes for any project in that org — and the call that
 * did confine a key was opt-in, which a third of {@code {projectId}} routes forgot. Every route
 * whose URI template carries {@code {projectId}} is now compared against the key's own project
 * regardless of what the handler does, unless it declares {@link ProjectScopeExempt}.
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
     * Enforces {@link RequireAccess}, for both JWT and API-key callers.
     *
     * <p>Before the scope check, not after: scope returns early for a JWT, so a role requirement
     * placed after it would not apply to dashboard callers — exactly the ones a VIEWER is.
     *
     * <p>An authentication that maps to no membership role is refused when a level above READ is
     * declared. That costs the admin endpoints nothing, since none of them declares one; what the
     * old pass-through covered was platform-admin tokens aimed at tenant handlers, safe only
     * because every annotated handler happens to take an {@code AuthContext}.
     */
    private void enforceAccessLevel(HandlerMethod handlerMethod, Authentication authentication) {
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

        // Deliberately the same RbacUtil the handlers call, rather than a second implementation
        // of "what write access means". Two implementations would be two things to keep in
        // step, and the whole point of this annotation is that there is one answer.
        if (required.value() == AccessLevel.OWNER) {
            RbacUtil.requireOwnerAccess(role);
        } else {
            RbacUtil.requireWriteAccess(role, scope);
        }
    }


    /**
     * Refuses a write from an account that has not proved it owns its address.
     *
     * <p>This lived only in the browser: {@code VerificationGate.tsx} greys the buttons out, and
     * the server issued an ordinary token to a {@code PENDING_VERIFICATION} account and asked
     * nothing further — login refuses {@code DISABLED} and nothing else. Anyone reaching past
     * the dashboard had the whole API.
     *
     * <p>Inert where verification is meaningless: with mail disabled, registration marks the
     * account verified on the spot, because an unsent token proves nothing about an address and
     * a gate with no key is just a locked-out user. So a self-hosted instance sees no change,
     * and an instance with open registration and a free tier gets the rule it needs.
     *
     * <p>Hung off {@link RequireAccess} rather than a path list, so it covers exactly what
     * writing covers. Reading stays open — the screen that tells the user to check their mail
     * is a read, and so is every screen they might be looking at when they find out.
     *
     * <p>API keys are not re-checked: a key exists only because someone created one, and
     * creating one is a write that passed this gate. Asking again would mean a user row read on
     * the hot path of every ingest to re-answer a settled question.
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
     * Refuses to change anything on behalf of a suspended organization.
     *
     * <p>Suspension used to be a word. {@code BillingStatus.SUSPENDED} is written by the dunning
     * scheduler when a grace period expires and read by nothing at all, so an organization that
     * had stopped paying — or one an operator wanted stopped for abuse — went on ingesting and
     * delivering exactly as before. There was also no way for an operator to suspend anyone
     * except by editing the database.
     *
     * <p>Keyed off the HTTP method rather than {@link RequireAccess}, unlike the verification
     * gate next to it, and for a specific reason: ingest carries no access-level annotation, and
     * ingest is the thing a suspension most needs to stop. Reads stay open so the tenant can
     * sign in and be told why, and so support can look at the same screens they can.
     *
     * <p>The reason the operator wrote is returned to the caller. That is deliberate — a tenant
     * discovering they are suspended should not have to open a ticket to find out what for —
     * and it is why the field is documented as something a customer can be shown.
     */
    void enforceNotSuspended(HttpServletRequest request, Authentication authentication) {
        if (READ_METHODS.contains(request.getMethod())) {
            return;
        }

        UUID organizationId;
        if (authentication instanceof JwtAuthenticationToken jwt) {
            organizationId = jwt.getOrganizationId();
        } else if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
            organizationId = apiKey.getOrganizationId();
        } else {
            // Unauthenticated, or the platform admin - who is the one able to lift a suspension
            // and must not be locked out by it.
            return;
        }

        suspensionCheck.suspensionReason(organizationId).ifPresent(reason -> {
            log.warn("Write refused: organization {} is suspended ({})", organizationId, reason);
            throw new ForbiddenException("This organization is suspended and cannot make changes."
                    + (reason.isBlank() ? "" : " Reason: " + reason));
        });
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

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

    /**
     * Structural, path-based project-tenancy guard. Runs for every request,
     * independent of the {@link RequireScope} check above and of anything the handler
     * method itself does.
     */
    private void enforceProjectScope(HttpServletRequest request, HandlerMethod handlerMethod,
                                      Authentication authentication) {
        if (!(authentication instanceof ApiKeyAuthenticationToken apiKeyAuth)) {
            // JWT / platform-admin auth: project access within an org is governed by
            // org membership at the service layer. Only API
            // keys are meant to be confined to a single project.
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
