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

// Three handlers once shipped reachable by a VIEWER: the role check was imperative and opt-in.
@Tag("ratchet")
class MutatingHandlerAccessDeclarationTest {

    private static final String CONTROLLER_PACKAGE = "com.webhook.platform.api.controller";

    private static final List<Class<? extends Annotation>> MUTATING_MAPPINGS =
            List.of(PostMapping.class, PutMapping.class, PatchMapping.class, DeleteMapping.class);

    // Adding an entry is a security decision: say why, and prefer annotating.
    private static final Set<String> DOCUMENTED_EXEMPTIONS = new TreeSet<>(Set.of(
            // Unauthenticated by design: public paths in SecurityConfig.
            "AuthController.register",
            "AuthController.login",
            "AuthController.refreshToken",
            // Before any session: the one-time code from the Google callback authorises it.
            "AuthController.exchangeSignInCode",
            "AuthController.logout",
            "AuthController.verifyEmail",
            "AuthController.resendVerification",
            "AuthController.forgotPassword",
            "AuthController.resetPassword",
            // Opened from a mailed link: the single-use token authorises it.
            "EmailChangeController.confirm",
            "EmailChangeController.cancelByToken",
            "DeviceAuthController.initiateDeviceAuth",
            "DeviceAuthController.pollDeviceToken",
            "BillingController.handleWebhook",
            "IngressController.receiveWebhook",
            // The public webhook tester: anonymous, rate-limited, no tenant data.
            "PublicBinController.create",
            // The public contact form: anonymous, rate-limited, mails only the support address.
            "PublicContactController.send",
            // The public demo: anonymous, off unless DEMO_ENABLED; mints a read-only Viewer token.
            "PublicDemoController.createSession",
            // MCP OAuth endpoints: called by an app with its client credentials, never a member.
            "McpOAuthController.registerOAuthClient",
            "McpOAuthController.issueOAuthToken",
            "McpOAuthController.revokeOAuthToken",

            // The caller's own account, not tenant data: a Viewer may change their own password.
            "AuthController.changePassword",
            "AuthController.updateProfile",
            // Anyone, even unverified, may fix their own address; the service asks for the password instead.
            "EmailChangeController.request",
            "EmailChangeController.resend",
            "EmailChangeController.cancel",
            "DeviceAuthController.approveDeviceCode",
            "DeviceAuthController.denyDeviceCode",
            "MemberController.acceptInvite",

            // Gated on requireJwt() plus the service's owner check; an API key must never reach these.
            "MemberController.addMember",
            "MemberController.changeMemberRole",
            "MemberController.removeMember",
            "MemberController.reissueInvite",

            // Platform-admin only via SecurityConfig; a platform admin holds no membership role.
            "EncryptionAdminController.rotateEncryptionKeys",
            // Same credential: an access level would refuse the only caller meant to make it.
            "PlatformAdminOrganizationController.suspend",
            "PlatformAdminOrganizationController.reinstate",

            // A portal session holds no membership role; PortalService confines rows to its Consumer.
            "PortalController.portalCreateEndpoint",
            "PortalController.portalUpdateEndpoint",
            "PortalController.portalDeleteEndpoint",
            "PortalController.portalRotateEndpointSecret",
            "PortalController.portalRetryDelivery",

            // The API key is the intended caller; these carry @RequireScope instead.
            "EventController.ingestEvent",

            // POST-shaped reads: they persist nothing.
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
        // The annotation does not license deleting the call: a reordered interceptor would leave nothing.
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
