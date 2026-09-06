package com.webhook.platform.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ratchet over API-key scope declarations on state-changing handlers.
 *
 * <p>Scope is enforced by {@link ScopeEnforcementInterceptor}, and its default when a handler
 * carries no {@code @RequireScope} is to <em>allow</em>. That default is why the gap this test
 * exists to close was invisible: {@code EndpointController.testEndpoint} fired a signed
 * outbound request from the platform with no scope and no role check at all, while its sibling
 * {@code rotateSecret} was guarded — nothing flagged the difference.
 *
 * <p>This test does not require every mutating handler to declare a scope. A number of them
 * legitimately cannot: authentication endpoints mint the credential rather than consume one,
 * public webhook receivers are unauthenticated by design, and org-level handlers are gated on
 * {@code requireOwnerAccess()} / {@code @RequireOrgAccess} instead. Those are frozen in
 * {@link #DOCUMENTED_EXEMPTIONS} below, each with a stated reason.
 *
 * <p>What it does guarantee is that the set cannot grow silently. A new POST/PUT/PATCH/DELETE
 * handler without {@code @RequireScope} fails the build until someone either annotates it or
 * adds it here with a justification — which is the review this codebase did not get.
 *
 * <p>Deliberately a plain {@code *Test}: it is pure reflection over the classpath, so it must
 * run in the no-Docker unit job. Do not rename it to {@code *RbacTest} — that routes it to the
 * Testcontainers job for no reason (see {@code scripts/check-test-routing.sh}).
 */
@Tag("ratchet")
class MutatingHandlerScopeDeclarationTest {

    private static final String CONTROLLER_PACKAGE = "com.webhook.platform.api.controller";

    private static final List<Class<? extends Annotation>> MUTATING_MAPPINGS =
            List.of(PostMapping.class, PutMapping.class, PatchMapping.class, DeleteMapping.class);

    /**
     * Handlers that change state but carry no {@code @RequireScope}, with the reason each is
     * acceptable. Format is {@code SimpleClassName.methodName}.
     *
     * <p>Adding an entry here is a security decision — say why, and prefer annotating instead.
     */
    private static final Set<String> DOCUMENTED_EXEMPTIONS = new TreeSet<>(Set.of(
            // Authentication: these mint or exchange the credential itself, so an API-key
            // scope cannot apply. Public paths in SecurityConfig.
            "AuthController.register",
            "AuthController.login",
            "AuthController.refreshToken",
            "AuthController.logout",
            "AuthController.verifyEmail",
            "AuthController.resendVerification",
            "AuthController.changePassword",
            "AuthController.updateProfile",
            "AuthController.forgotPassword",
            "AuthController.resetPassword",
            "DeviceAuthController.initiateDeviceAuth",
            "DeviceAuthController.pollDeviceToken",
            "DeviceAuthController.approveDeviceCode",
            "DeviceAuthController.denyDeviceCode",

            // Act on the caller's own sign-ins rather than on organization data, and are gated
            // on auth.requireJwt(), which rejects an API key outright — strictly stronger than
            // any scope a key could hold. An API key has no session to revoke and no membership
            // to switch between, so a scope is not the question these ask.
            "AuthController.revokeSession",
            "AuthController.revokeAllSessions",
            "AuthController.switchOrganization",

            // Owner-level org and billing operations: gated on requireOwnerAccess(), which is
            // strictly stronger than any API-key scope (API keys never hold OWNER).
            "BillingController.updateBillingInfo",
            "BillingController.changePlan",
            "BillingController.createCheckout",
            "BillingController.createPortal",
            "BillingController.cancelSubscription",
            "OrganizationController.updateOrganization",
            "OrganizationController.deleteOrganization",
            "MemberController.addMember",
            "MemberController.changeMemberRole",
            "MemberController.removeMember",
            "MemberController.reissueInvite",
            "MemberController.acceptInvite",
            // Same reason, and stronger than the three above: suspend and reinstate declare
            // @RequireAccess(OWNER), which the interceptor enforces for every caller. An API key
            // holds MembershipRole.API_KEY and so is refused whatever its scope — a scope
            // annotation could only weaken that.
            "MemberController.suspendMember",
            "MemberController.reinstateMember",

            // Unauthenticated by design — whitelisted public paths in SecurityConfig.
            "BillingController.handleWebhook",
            "IngressController.receiveWebhook",

            // Platform-admin only, gated on the PLATFORM_ADMIN authority for /api/v1/admin/**
            // in SecurityConfig rather than on a tenant scope.
            "EncryptionAdminController.rotateEncryptionKeys",
            // Same: an API key belongs to a project inside one organization, and suspension is
            // an act on an organization from outside it. No scope answers that question.
            "PlatformAdminOrganizationController.suspend",
            "PlatformAdminOrganizationController.reinstate",

            // POST-shaped reads: these compute a response from caller-supplied input and
            // persist nothing. They are POSTs only because the input does not fit in a query
            // string. Contrast TransformPreviewController.deliveryDryRun, which DOES return a
            // real HMAC signature for a stored endpoint and is therefore annotated.
            "PiiMaskingController.previewSanitization",
            "TransformPreviewController.preview",

            // Guarded by hand with auth.requireWriteAccess(), which rejects both a VIEWER role
            // and a READ_ONLY API key (RbacUtil.requireWriteAccess). Enforced, but through a
            // different layer than every sibling controller — worth converging on the
            // annotation, not a security gap today.
            "ProjectEventsController.sendTestEvent",
            "TunnelController.create",
            "TunnelController.close"
    ));

    @Test
    @DisplayName("every state-changing handler declares @RequireScope, or is a documented exemption")
    void mutatingHandlersDeclareScope() {
        Set<String> undeclared = new TreeSet<>();
        Set<String> allMutating = new TreeSet<>();

        for (Class<?> controller : findControllers()) {
            boolean classLevelScope = controller.isAnnotationPresent(RequireScope.class);
            for (Method method : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || !isMutating(method)) {
                    continue;
                }
                String id = controller.getSimpleName() + "." + method.getName();
                allMutating.add(id);
                if (!classLevelScope && !method.isAnnotationPresent(RequireScope.class)) {
                    undeclared.add(id);
                }
            }
        }

        assertTrue(allMutating.size() > 50,
                "scan found only " + allMutating.size() + " mutating handlers — the classpath scan "
                        + "is probably broken, which would make this test vacuous");

        Set<String> unexpected = new TreeSet<>(undeclared);
        unexpected.removeAll(DOCUMENTED_EXEMPTIONS);
        assertEquals(Set.of(), unexpected,
                "These state-changing handlers carry no @RequireScope. The interceptor's default "
                        + "with no annotation is to ALLOW, so each is reachable by a READ_ONLY API "
                        + "key. Annotate them, or add them to DOCUMENTED_EXEMPTIONS with a reason.");
    }

    @Test
    @DisplayName("the exemption list has no stale entries")
    void exemptionsAreAllStillReachable() {
        Set<String> undeclared = new TreeSet<>();
        for (Class<?> controller : findControllers()) {
            boolean classLevelScope = controller.isAnnotationPresent(RequireScope.class);
            for (Method method : controller.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && isMutating(method)
                        && !classLevelScope && !method.isAnnotationPresent(RequireScope.class)) {
                    undeclared.add(controller.getSimpleName() + "." + method.getName());
                }
            }
        }

        Set<String> stale = new TreeSet<>(DOCUMENTED_EXEMPTIONS);
        stale.removeAll(undeclared);
        assertEquals(Set.of(), stale,
                "These entries are no longer needed — the handler was annotated, renamed or "
                        + "removed. Drop them so the list keeps meaning something.");
    }

    private boolean isMutating(Method method) {
        return MUTATING_MAPPINGS.stream().anyMatch(method::isAnnotationPresent);
    }

    private List<Class<?>> findControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return scanner.findCandidateComponents(CONTROLLER_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(name -> {
                    try {
                        return Class.forName(name);
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Scanned but could not load " + name, e);
                    }
                })
                .sorted(java.util.Comparator.comparing(Class::getName))
                .collect(java.util.stream.Collectors.<Class<?>>toList());
    }
}
