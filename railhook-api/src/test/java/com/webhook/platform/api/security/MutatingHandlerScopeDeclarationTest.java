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

// No @RequireScope means allow, so the exemption set must not grow silently.
@Tag("ratchet")
class MutatingHandlerScopeDeclarationTest {

    private static final String CONTROLLER_PACKAGE = "com.webhook.platform.api.controller";

    private static final List<Class<? extends Annotation>> MUTATING_MAPPINGS =
            List.of(PostMapping.class, PutMapping.class, PatchMapping.class, DeleteMapping.class);

    // Adding an entry is a security decision: say why, and prefer annotating.
    private static final Set<String> DOCUMENTED_EXEMPTIONS = new TreeSet<>(Set.of(
            // A portal session is the caller; SecurityConfig refuses an API key on /api/v1/portal/**.
            "PortalController.portalCreateEndpoint",
            "PortalController.portalUpdateEndpoint",
            "PortalController.portalDeleteEndpoint",
            "PortalController.portalRotateEndpointSecret",
            "PortalController.portalRetryDelivery",

            // Authentication mints the credential itself: public paths in SecurityConfig.
            "AuthController.register",
            "AuthController.login",
            "AuthController.refreshToken",
            // Public like login: the one-time code is the credential.
            "AuthController.exchangeSignInCode",
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

            // The caller's own sign-ins, gated on requireJwt(), which rejects an API key outright.
            "AuthController.revokeSession",
            "AuthController.revokeAllSessions",
            "AuthController.switchOrganization",

            // The caller's own account via requireUserId(), or public with the mailed token as credential.
            "EmailChangeController.request",
            "EmailChangeController.resend",
            "EmailChangeController.cancel",
            "EmailChangeController.confirm",
            "EmailChangeController.cancelByToken",

            // Gated on requireJwt(): an API key must never erase the human who created it.
            "AuthController.eraseOwnAccount",

            // Gated on requireJwt(); only appends to this installation's log.
            "ClientErrorController.report",

            // Gated on requireOwnerAccess(); API keys never hold OWNER.
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
            // @RequireAccess(OWNER) already refuses an API key whatever its scope.
            "MemberController.suspendMember",
            "MemberController.reinstateMember",

            // Unauthenticated by design: public paths in SecurityConfig.
            "BillingController.handleWebhook",
            "IngressController.receiveWebhook",
            // The public webhook tester: anonymous, rate-limited, no tenant data.
            "PublicBinController.create",
            // The public contact form: anonymous, rate-limited, mails only the support address.
            "PublicContactController.send",
            // The public demo: anonymous, off unless DEMO_ENABLED; an API key has no use for it.
            "PublicDemoController.createSession",
            // MCP OAuth endpoints: an app's client credentials authorise each call, never an API key.
            "McpOAuthController.registerOAuthClient",
            "McpOAuthController.issueOAuthToken",
            "McpOAuthController.revokeOAuthToken",

            // Platform-admin only via SecurityConfig, not a tenant scope.
            "EncryptionAdminController.rotateEncryptionKeys",
            // Suspension acts on an organization from outside it; no project scope answers that.
            "PlatformAdminOrganizationController.suspend",
            "PlatformAdminOrganizationController.reinstate",

            // POST-shaped reads: they persist nothing.
            "PiiMaskingController.previewSanitization",
            "TransformPreviewController.preview",

            // Guarded by hand with requireWriteAccess(); worth converging on the annotation.
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
