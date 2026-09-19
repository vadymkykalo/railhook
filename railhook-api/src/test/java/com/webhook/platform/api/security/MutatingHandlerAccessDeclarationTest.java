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
 * Ratchet over {@link RequireAccess} declarations on state-changing handlers.
 *
 * <p>The sibling of {@code MutatingHandlerScopeDeclarationTest}, aimed at the other half of
 * the structural-tenancy work. That one covers what an API key's <em>scope</em> permits; this
 * one covers what the caller's <em>role</em> must be — the check that was only ever an
 * imperative {@code auth.requireWriteAccess()} in a handler body, defaulting to allow when
 * somebody did not write it. Three handlers shipped reachable by a VIEWER JWT and a READ_ONLY
 * API key for exactly that reason.
 *
 * <p>Two separate lists rather than one, because the two questions have genuinely different
 * answers: {@code EventController.ingestEvent} declares a scope and no role (an API key is the
 * intended caller), while {@code MemberController.addMember} declares a role and no scope (an
 * API key must never call it at all).
 *
 * <p>Deliberately a plain {@code *Test}: pure reflection over the classpath, so it must run in
 * the no-Docker unit job — see {@code scripts/check-test-routing.sh}.
 */
@Tag("ratchet")
class MutatingHandlerAccessDeclarationTest {

    private static final String CONTROLLER_PACKAGE = "com.webhook.platform.api.controller";

    private static final List<Class<? extends Annotation>> MUTATING_MAPPINGS =
            List.of(PostMapping.class, PutMapping.class, PatchMapping.class, DeleteMapping.class);

    /**
     * Handlers that change state but declare no {@link RequireAccess}, with the reason each is
     * acceptable. Format is {@code SimpleClassName.methodName}.
     *
     * <p>Adding an entry here is a security decision — say why, and prefer annotating instead.
     */
    private static final Set<String> DOCUMENTED_EXEMPTIONS = new TreeSet<>(Set.of(
            // Unauthenticated by design: whitelisted public paths in SecurityConfig. There is
            // no caller identity to require a level of.
            "AuthController.register",
            "AuthController.login",
            "AuthController.refreshToken",
            // Called before any session exists: the caller holds a one-time code from the Google
            // callback, not a membership, and the code is what authorises the call.
            "AuthController.exchangeSignInCode",
            "AuthController.logout",
            "AuthController.verifyEmail",
            "AuthController.resendVerification",
            "AuthController.forgotPassword",
            "AuthController.resetPassword",
            // Opened from a mailed link, possibly with no session at all: the single-use token
            // sent to the new (confirm) or old (cancel) address is what authorises the call.
            "EmailChangeController.confirm",
            "EmailChangeController.cancelByToken",
            "DeviceAuthController.initiateDeviceAuth",
            "DeviceAuthController.pollDeviceToken",
            "BillingController.handleWebhook",
            "IngressController.receiveWebhook",
            // The public site's webhook tester: anonymous by design (/api/v1/public/** in
            // SecurityConfig), rate-limited per address, and it touches no tenant data at all.
            "PublicBinController.create",
            // The public site's contact form: anonymous by design, rate-limited per address and
            // behind the challenge; it only ever mails the deployment's own support address.
            "PublicContactController.send",
            // The public site's live demo: anonymous by design (/api/v1/public/**), off unless
            // DEMO_ENABLED, rate-limited per address and behind the challenge. It mints a Viewer
            // token for the demo organization, which ScopeEnforcementInterceptor keeps read-only.
            "PublicDemoController.createSession",
            // The MCP server's OAuth protocol endpoints: called by an app with its client
            // credentials, a code + PKCE verifier or a refresh token, never by a member, so there
            // is no role to require. Public paths in McpSecurityConfig, outside /api.
            "McpOAuthController.registerOAuthClient",
            "McpOAuthController.issueOAuthToken",
            "McpOAuthController.revokeOAuthToken",

            // Act on the caller's own account rather than on tenant data, so a membership role
            // is not the right question: a Viewer may change their own password.
            "AuthController.changePassword",
            "AuthController.updateProfile",
            // A Viewer may change their own sign-in address, and an unverified account must be
            // able to — it is how a mistyped address gets fixed. The service asks for the
            // password, the CAPTCHA or a recent sign-in instead of a role.
            "EmailChangeController.request",
            "EmailChangeController.resend",
            "EmailChangeController.cancel",
            "DeviceAuthController.approveDeviceCode",
            "DeviceAuthController.denyDeviceCode",
            "MemberController.acceptInvite",

            // Org-level membership management, gated on requireJwt() plus the service's own
            // owner check rather than on a handler-level role. An API key must never reach
            // these at all, which requireJwt() — not an AccessLevel — is what states.
            "MemberController.addMember",
            "MemberController.changeMemberRole",
            "MemberController.removeMember",
            "MemberController.reissueInvite",

            // Platform-admin only, gated on the PLATFORM_ADMIN authority for /api/v1/admin/**
            // in SecurityConfig. A platform admin holds no membership role, so the interceptor
            // deliberately lets these through untouched.
            "EncryptionAdminController.rotateEncryptionKeys",
            // Same credential, same reason: suspending a tenant is the deployment operator's
            // act, not any member's, and declaring an access level here would refuse the only
            // caller who is supposed to make it.
            "PlatformAdminOrganizationController.suspend",
            "PlatformAdminOrganizationController.reinstate",

            // The customer portal: authenticated by a portal session, which holds no membership
            // role at all — SecurityConfig admits only PORTAL_SESSION on /api/v1/portal/**, and
            // PortalService confines every row to the session's Consumer. The session is the whole
            // of the caller's authority, so there is no level to require.
            "PortalController.portalCreateEndpoint",
            "PortalController.portalUpdateEndpoint",
            "PortalController.portalDeleteEndpoint",
            "PortalController.portalRotateEndpointSecret",
            "PortalController.portalRetryDelivery",

            // The API key IS the intended caller, and what it may do is decided by its scope:
            // these carry @RequireScope instead. See MutatingHandlerScopeDeclarationTest.
            "EventController.ingestEvent",

            // POST-shaped reads: they compute a response from caller-supplied input and persist
            // nothing, and are POSTs only because the input does not fit in a query string.
            // Contrast TransformPreviewController.deliveryDryRun, which returns a real HMAC
            // signature for a stored Endpoint and is therefore annotated WRITE.
            "PiiMaskingController.previewSanitization",
            "TransformPreviewController.preview",
            "ReplayController.estimate"
    ));

    @Test
    @DisplayName("every state-changing handler declares @RequireAccess, or is a documented exemption")
    void mutatingHandlersDeclareAccessLevel() {
        Set<String> undeclared = new TreeSet<>();
        Set<String> allMutating = new TreeSet<>();

        for (Class<?> controller : findControllers()) {
            boolean classLevel = controller.isAnnotationPresent(RequireAccess.class);
            for (Method method : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || !isMutating(method)) {
                    continue;
                }
                String id = controller.getSimpleName() + "." + method.getName();
                allMutating.add(id);
                if (!classLevel && !method.isAnnotationPresent(RequireAccess.class)) {
                    undeclared.add(id);
                }
            }
        }

        assertTrue(allMutating.size() > 50,
                "Only " + allMutating.size() + " mutating handlers were found. The classpath scan "
                        + "is broken and this test is vacuous.");

        undeclared.removeAll(DOCUMENTED_EXEMPTIONS);
        assertTrue(undeclared.isEmpty(),
                "These state-changing handlers say nothing about who may call them:\n  "
                        + String.join("\n  ", undeclared)
                        + "\n\nAnnotate each with @RequireAccess, or — if no membership role is the "
                        + "right question for it — add it to DOCUMENTED_EXEMPTIONS with a reason "
                        + "someone else can check.\n");
    }

    @Test
    @DisplayName("the exemption list has no entries that stopped describing anything")
    void exemptionsStillDescribeUndeclaredHandlers() {
        Set<String> stillUndeclared = new TreeSet<>();
        for (Class<?> controller : findControllers()) {
            boolean classLevel = controller.isAnnotationPresent(RequireAccess.class);
            for (Method method : controller.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && isMutating(method)
                        && !classLevel && !method.isAnnotationPresent(RequireAccess.class)) {
                    stillUndeclared.add(controller.getSimpleName() + "." + method.getName());
                }
            }
        }

        Set<String> stale = new TreeSet<>(DOCUMENTED_EXEMPTIONS);
        stale.removeAll(stillUndeclared);
        assertEquals(Set.of(), stale,
                "These exemptions no longer describe anything — the handler was annotated, "
                        + "renamed or removed. Drop them so the list keeps meaning something.");
    }

    @Test
    @DisplayName("an annotated handler still calls the imperative guard, as defence in depth")
    void annotationDidNotReplaceTheImperativeCheck() {
        // The annotation makes the requirement visible and its absence loud. It is not licence
        // to delete the call: the interceptor runs before the handler, and a future change that
        // bypasses or reorders it would otherwise leave nothing behind.
        long annotated = findControllers().stream()
                .flatMap(c -> List.of(c.getDeclaredMethods()).stream())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && isMutating(m))
                .filter(m -> m.isAnnotationPresent(RequireAccess.class))
                .count();
        assertTrue(annotated >= 70,
                "Only " + annotated + " mutating handlers carry @RequireAccess; 79 were annotated "
                        + "when this landed. A large drop means they are being removed rather than "
                        + "the exemption list being extended.");
    }

    private static boolean isMutating(Method method) {
        return MUTATING_MAPPINGS.stream().anyMatch(method::isAnnotationPresent);
    }

    private static List<Class<?>> findControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return scanner.findCandidateComponents(CONTROLLER_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(name -> {
                    try {
                        return Class.forName(name);
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Controller on the classpath but not loadable: " + name, e);
                    }
                })
                .collect(java.util.stream.Collectors.<Class<?>>toList());
    }
}
