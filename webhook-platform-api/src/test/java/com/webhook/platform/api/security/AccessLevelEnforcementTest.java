package com.webhook.platform.api.security;

import com.webhook.platform.api.controller.EndpointController;
import com.webhook.platform.api.controller.OrganizationController;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.exception.ForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@code MutatingHandlerAccessDeclarationTest} proves every state-changing handler
 * <em>declares</em> an {@link AccessLevel}. This proves the declaration is actually
 * <em>enforced</em> — that {@link RequireAccess} is a guard and not decoration.
 *
 * <p>Worth separating: a ratchet over annotations passes just as happily when the interceptor
 * that reads them has been unregistered, reordered behind an early return, or quietly stopped
 * being called.
 */
class AccessLevelEnforcementTest {

    private final ScopeEnforcementInterceptor interceptor = new ScopeEnforcementInterceptor(org -> java.util.Optional.empty());

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private boolean preHandle(Object handler, Authentication auth) {
        SecurityContextHolder.getContext().setAuthentication(auth);
        return interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler);
    }

    private static HandlerMethod handlerFor(Class<?> controller, String methodName) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no such handler: " + methodName));
        return new HandlerMethod(mock(controller), method);
    }

    private static Authentication jwt(MembershipRole role) {
        return new JwtAuthenticationToken(UUID.randomUUID(), UUID.randomUUID(), role, true, List.of());
    }

    private static Authentication apiKey(ApiKeyScope scope) {
        return new ApiKeyAuthenticationToken("test-key", UUID.randomUUID(), UUID.randomUUID(), scope, List.of());
    }

    // ── the declarations exist where these tests assume they do ───────────────────

    @Test
    @DisplayName("the handlers under test really carry the annotations this test relies on")
    void fixturesAreAnnotatedAsAssumed() {
        RequireAccess write = handlerFor(EndpointController.class, "createEndpoint")
                .getMethodAnnotation(RequireAccess.class);
        assertNotNull(write, "createEndpoint lost its @RequireAccess — this test would silently pass");
        assertTrue(write.value() == AccessLevel.WRITE);

        RequireAccess owner = handlerFor(OrganizationController.class, "deleteOrganization")
                .getMethodAnnotation(RequireAccess.class);
        assertNotNull(owner, "deleteOrganization lost its @RequireAccess");
        assertTrue(owner.value() == AccessLevel.OWNER);
    }

    @Nested
    @DisplayName("WRITE")
    class Write {

        private final HandlerMethod handler = handlerFor(EndpointController.class, "createEndpoint");

        @Test
        @DisplayName("a Viewer is rejected before the handler runs")
        void viewerRejected() {
            ForbiddenException e = assertThrows(ForbiddenException.class,
                    () -> preHandle(handler, jwt(MembershipRole.VIEWER)));
            assertTrue(e.getMessage().contains("read-only"), e.getMessage());
        }

        @Test
        @DisplayName("a Developer and an Owner both pass")
        void developerAndOwnerPass() {
            assertDoesNotThrow(() -> preHandle(handler, jwt(MembershipRole.DEVELOPER)));
            assertDoesNotThrow(() -> preHandle(handler, jwt(MembershipRole.OWNER)));
        }

        @Test
        @DisplayName("a READ_ONLY API key is rejected")
        void readOnlyKeyRejected() {
            assertThrows(ForbiddenException.class, () -> preHandle(handler, apiKey(ApiKeyScope.READ_ONLY)));
        }
    }

    @Nested
    @DisplayName("OWNER")
    class Owner {

        private final HandlerMethod handler = handlerFor(OrganizationController.class, "deleteOrganization");

        @Test
        @DisplayName("a Developer is rejected — WRITE is not enough")
        void developerRejected() {
            ForbiddenException e = assertThrows(ForbiddenException.class,
                    () -> preHandle(handler, jwt(MembershipRole.DEVELOPER)));
            assertTrue(e.getMessage().contains("owners"), e.getMessage());
        }

        @Test
        @DisplayName("an Owner passes")
        void ownerPasses() {
            assertDoesNotThrow(() -> preHandle(handler, jwt(MembershipRole.OWNER)));
        }

        @Test
        @DisplayName("a READ_WRITE API key is rejected: a key never holds OWNER")
        void readWriteKeyStillRejected() {
            assertThrows(ForbiddenException.class, () -> preHandle(handler, apiKey(ApiKeyScope.READ_WRITE)));
        }
    }

    @Nested
    @DisplayName("callers the level does not apply to")
    class NotApplicable {

        @Test
        @DisplayName("an unannotated handler lets a Viewer through")
        void unannotatedIsUnaffected() {
            // AuthController.login is a documented exemption: unauthenticated by design.
            HandlerMethod handler = handlerFor(
                    com.webhook.platform.api.controller.AuthController.class, "login");
            assertDoesNotThrow(() -> preHandle(handler, jwt(MembershipRole.VIEWER)));
        }

        @Test
        @DisplayName("a platform-admin token is refused by a level it cannot satisfy")
        void platformAdminCannotSatisfyAMembershipLevel() {
            // Reversed deliberately. This used to pass through, on
            // the grounds that running RbacUtil against a platform admin would reject the one
            // caller /api/v1/admin/** is for. That reason does not reach here: no admin handler
            // carries @RequireAccess (EncryptionAdminController is gated on the PLATFORM_ADMIN
            // authority in SecurityConfig), so the pass-through only ever applied to *tenant*
            // handlers, where a platform admin is not the intended caller. It was already
            // refused there — by AuthContextArgumentResolver, one step later, because every
            // annotated handler happens to take an AuthContext. Refusing here means the guard
            // no longer depends on that coincidence holding.
            HandlerMethod handler = handlerFor(OrganizationController.class, "deleteOrganization");
            ForbiddenException e = assertThrows(ForbiddenException.class,
                    () -> preHandle(handler, new PlatformAdminAuthenticationToken()));
            assertTrue(e.getMessage().contains("membership role"), e.getMessage());
        }

        @Test
        @DisplayName("a platform-admin token still reaches the admin handlers, which declare no level")
        void platformAdminStillReachesAdminHandlers() {
            // The blast radius of the reversal above, stated as a test: /api/v1/admin/** works
            // because it declares nothing, not because the interceptor makes an exception.
            HandlerMethod handler = handlerFor(
                    com.webhook.platform.api.controller.EncryptionAdminController.class, "rotateEncryptionKeys");
            assertDoesNotThrow(() -> preHandle(handler, new PlatformAdminAuthenticationToken()));
        }

        @Test
        @DisplayName("an unauthenticated request is refused by a declared level rather than waved through")
        void anonymousCannotSatisfyAMembershipLevel() {
            HandlerMethod handler = handlerFor(OrganizationController.class, "deleteOrganization");
            assertThrows(ForbiddenException.class, () -> preHandle(handler, null));
        }

        @Test
        @DisplayName("a non-HandlerMethod handler is ignored rather than blowing up")
        void staticResourceHandlerIgnored() {
            assertDoesNotThrow(() -> preHandle(new Object(), jwt(MembershipRole.VIEWER)));
        }
    }
}
