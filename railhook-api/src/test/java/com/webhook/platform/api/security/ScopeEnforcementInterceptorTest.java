package com.webhook.platform.api.security;

import com.webhook.platform.api.controller.AuthController;
import com.webhook.platform.api.controller.EncryptionAdminController;
import com.webhook.platform.api.controller.EndpointController;
import com.webhook.platform.api.controller.OrganizationController;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.exception.DemoReadOnlyException;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.common.demo.DemoTenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ScopeEnforcementInterceptorTest {

    private final ScopeEnforcementInterceptor interceptor = new ScopeEnforcementInterceptor(org -> Optional.empty());

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

    private static HandlerMethod handlerFor(Object bean, String methodName) throws NoSuchMethodException {
        return new HandlerMethod(bean, bean.getClass().getDeclaredMethod(methodName));
    }

    private static JwtAuthenticationToken jwt(MembershipRole role) {
        return new JwtAuthenticationToken(UUID.randomUUID(), UUID.randomUUID(), role, true, List.of());
    }

    private static ApiKeyAuthenticationToken apiKey(ApiKeyScope scope) {
        return new ApiKeyAuthenticationToken("test-key", UUID.randomUUID(), UUID.randomUUID(), scope, List.of());
    }

    static class DemoHandlers {
        public void write() {
        }

        @AllowedInDemo(reason = "test")
        public void allowed() {
        }

        @RefusedInDemo
        public void export() {
        }

        /** The dry-run's shape: a write level, lifted for the demo because it is on the list. */
        @AllowedInDemo(reason = "test")
        @RequireAccess(AccessLevel.WRITE)
        public void allowedButDeclaresWrite() {
        }

        @RequireAccess(AccessLevel.WRITE)
        public void declaresWrite() {
        }
    }

    @RequireAccess(AccessLevel.WRITE)
    static class WriteHandler {
        public void handle() {
        }
    }

    static class ReadHandler {
        public void handle() {
        }
    }

    /**
     * {@code MutatingHandlerAccessDeclarationTest} proves every state-changing handler
     * <em>declares</em> an {@link AccessLevel}. This proves the declaration is actually
     * <em>enforced</em> — that {@link RequireAccess} is a guard and not decoration.
     *
     * <p>Worth separating: a ratchet over annotations passes just as happily when the interceptor
     * that reads them has been unregistered, reordered behind an early return, or quietly stopped
     * being called.
     */
    @Nested
    class AccessLevelEnforcement {

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
                HandlerMethod handler = handlerFor(AuthController.class, "login");
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
                HandlerMethod handler = handlerFor(EncryptionAdminController.class, "rotateEncryptionKeys");
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

    /**
     * An API key reaches only its own project, checked by the interceptor on the real handler.
     *
     * <p>Worst concrete case: {@code EndpointController.rotateSecret} never called
     * {@code AuthContext.validateProjectAccess}, and {@code EndpointService.rotateSecret} checks
     * only organizationId — which AuthContext derives from the API key's own project, so it's the
     * same for every project in an org. A key scoped to "staging" could rotate a "production"
     * endpoint's signing secret and get it back in plaintext. The bug was that the handler never
     * called validateProjectAccess at all, not that the method behaves wrong. The fix moved the
     * check into ScopeEnforcementInterceptor, which now runs unconditionally against the actual
     * {@code EndpointController.rotateSecret} HandlerMethod, independent of what the handler itself
     * does. A full HTTP-level reproduction lives in ProjectScopeEnforcementIsolationTest.
     */
    @Nested
    class ApiKeyProjectScope {

        @Test
        void testRotateSecretHandler_apiKeyScopedToDifferentProject_blockedByInterceptor() throws NoSuchMethodException {
            UUID keyProjectId = UUID.randomUUID();
            UUID otherProjectId = UUID.randomUUID();

            HandlerMethod rotateSecretHandler = rotateSecretHandlerMethod();
            MockHttpServletRequest request = rotateSecretRequest(otherProjectId);
            authenticateAsApiKey(keyProjectId);

            ForbiddenException ex = assertThrows(ForbiddenException.class,
                    () -> interceptor.preHandle(request, new MockHttpServletResponse(), rotateSecretHandler));
            assertTrue(ex.getMessage().contains("does not have access to this project"));
        }

        @Test
        void testRotateSecretHandler_apiKeyScopedToOwnProject_allowedByInterceptor() throws NoSuchMethodException {
            UUID projectId = UUID.randomUUID();

            HandlerMethod rotateSecretHandler = rotateSecretHandlerMethod();
            MockHttpServletRequest request = rotateSecretRequest(projectId);
            authenticateAsApiKey(projectId);

            assertDoesNotThrow(
                    () -> interceptor.preHandle(request, new MockHttpServletResponse(), rotateSecretHandler));
        }

        private HandlerMethod rotateSecretHandlerMethod() throws NoSuchMethodException {
            Method method = EndpointController.class.getMethod("rotateSecret", UUID.class, UUID.class, AuthContext.class);
            return new HandlerMethod(mock(EndpointController.class), method);
        }

        private MockHttpServletRequest rotateSecretRequest(UUID pathProjectId) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST",
                    "/api/v1/projects/" + pathProjectId + "/endpoints/" + UUID.randomUUID() + "/rotate-secret");
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("projectId", pathProjectId.toString()));
            return request;
        }

        private void authenticateAsApiKey(UUID keyProjectId) {
            SecurityContextHolder.getContext().setAuthentication(
                    new ApiKeyAuthenticationToken("test-key", keyProjectId, UUID.randomUUID(), ApiKeyScope.READ_WRITE, List.of()));
        }
    }

    /**
     * The demo's read-only rule, as the interceptor applies it. {@code DemoSessionIntegrationTest}
     * walks every real handler with a real demo session; this pins who counts as the demo and what
     * the two annotations do.
     */
    @Nested
    class DemoReadOnlyGate {

        private HandlerMethod handler(String name) throws Exception {
            return handlerFor(new DemoHandlers(), name);
        }

        private MockHttpServletRequest request(String method) {
            return new MockHttpServletRequest(method, "/api/v1/anything");
        }

        private JwtAuthenticationToken demoSession() {
            return new JwtAuthenticationToken(DemoTenant.USER_ID, DemoTenant.ORGANIZATION_ID,
                    MembershipRole.VIEWER, true, true, Collections.emptyList());
        }

        @Test
        void aDemoSessionCannotWriteByAnyMethod() throws Exception {
            for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
                assertThrows(DemoReadOnlyException.class,
                        () -> interceptor.enforceDemoReadOnly(request(method), handler("write"), demoSession()), method);
            }
        }

        @Test
        void aDemoSessionReads() throws Exception {
            assertDoesNotThrow(() -> interceptor.enforceDemoReadOnly(request("GET"), handler("write"), demoSession()));
        }

        @Test
        void aDemoSessionCannotWriteEvenWhereNoHandlerMatched() {
            // A write that no controller answers is refused too, rather than falling through to
            // whatever else serves the path.
            assertThrows(DemoReadOnlyException.class,
                    () -> interceptor.enforceDemoReadOnly(request("POST"), new Object(), demoSession()));
        }

        @Test
        void onlyAnAllowedHandlerTakesAWriteFromTheDemo() throws Exception {
            assertDoesNotThrow(() -> interceptor.enforceDemoReadOnly(request("POST"), handler("allowed"), demoSession()));
        }

        @Test
        void anExportIsRefusedAlthoughItIsARead() throws Exception {
            assertThrows(DemoReadOnlyException.class,
                    () -> interceptor.enforceDemoReadOnly(request("GET"), handler("export"), demoSession()));
        }

        @Test
        void anyCredentialForTheDemoOrganizationIsTheDemo() throws Exception {
            // No demo claim, an OWNER role, an API key, a portal session: however a credential for the
            // demo organization came about, it is read-only.
            JwtAuthenticationToken ownerWithoutClaim = new JwtAuthenticationToken(UUID.randomUUID(),
                    DemoTenant.ORGANIZATION_ID, MembershipRole.OWNER, true, Collections.emptyList());
            ApiKeyAuthenticationToken demoKey = new ApiKeyAuthenticationToken("k", DemoTenant.PROJECT_ID,
                    DemoTenant.ORGANIZATION_ID, ApiKeyScope.READ_WRITE, Collections.emptyList());
            PortalSessionAuthenticationToken portal = new PortalSessionAuthenticationToken(UUID.randomUUID(),
                    DemoTenant.ORGANIZATION_ID, DemoTenant.PROJECT_ID, UUID.randomUUID());

            HandlerMethod write = handler("write");
            assertThrows(DemoReadOnlyException.class, () -> interceptor.enforceDemoReadOnly(request("POST"), write, ownerWithoutClaim));
            assertThrows(DemoReadOnlyException.class, () -> interceptor.enforceDemoReadOnly(request("POST"), write, demoKey));
            assertThrows(DemoReadOnlyException.class, () -> interceptor.enforceDemoReadOnly(request("POST"), write, portal));
        }

        // ── The access level, for a handler the demo may call ──────────
        //
        // A demo session is a VIEWER, so a handler declaring WRITE refuses it however carefully
        // @AllowedInDemo was reviewed. The level is lifted for exactly that pair — demo caller, and
        // a handler on the frozen list — so that "the demo may call this" means what it says. The
        // capability the level was protecting is taken off the answer instead; for the one handler
        // this applies to, that is DemoDryRunMask.

        @Test
        void anAllowedHandlerIsNotThenRefusedByItsOwnWriteLevel() throws Exception {
            assertDoesNotThrow(() -> interceptor.enforceAccessLevel(handler("allowedButDeclaresWrite"), demoSession()));
        }

        @Test
        void aWriteLevelStillRefusesTheDemoWhereTheHandlerIsNotOnTheList() throws Exception {
            assertThrows(ForbiddenException.class,
                    () -> interceptor.enforceAccessLevel(handler("declaresWrite"), demoSession()));
        }

        @Test
        void theListLiftsNothingForAnybodyButTheDemo() throws Exception {
            assertThrows(ForbiddenException.class,
                    () -> interceptor.enforceAccessLevel(handler("allowedButDeclaresWrite"), jwt(MembershipRole.VIEWER)));
        }

        @Test
        void everyoneElseIsUntouched() throws Exception {
            JwtAuthenticationToken owner = jwt(MembershipRole.OWNER);
            assertDoesNotThrow(() -> interceptor.enforceDemoReadOnly(request("POST"), handler("write"), owner));
            assertDoesNotThrow(() -> interceptor.enforceDemoReadOnly(request("GET"), handler("export"), owner));
            assertDoesNotThrow(() -> interceptor.enforceDemoReadOnly(request("POST"), handler("write"), null));
        }
    }

    /**
     * Email verification, enforced where it can actually be enforced.
     *
     * <p>It was a component in the dashboard. {@code VerificationGate.tsx} greys out the buttons of
     * an account whose status is {@code PENDING_VERIFICATION}, and nothing on the server ever asked:
     * login refuses only {@code DISABLED}, so an unverified account received an ordinary access token
     * and could do every one of those things with curl. On a self-hosted instance that is close to
     * harmless — mail is off by default, registration marks the account verified because an unsent
     * email proves nothing, and the gate never engages. On anything with open registration and a
     * free tier it is the difference between an address someone owns and an address someone typed.
     *
     * <p>The check hangs off {@link RequireAccess} rather than a list of paths, so it covers what
     * writing covers and nothing else: reading stays open, which is what lets an unverified user see
     * the dashboard telling them to check their mail.
     */
    @Nested
    class VerificationGate {

        private HandlerMethod handler(Class<?> type) throws Exception {
            return new HandlerMethod(type.getDeclaredConstructor().newInstance(),
                    type.getDeclaredMethod("handle"));
        }

        private JwtAuthenticationToken caller(boolean emailVerified) {
            return new JwtAuthenticationToken(UUID.randomUUID(), UUID.randomUUID(),
                    MembershipRole.OWNER, emailVerified, Collections.emptyList());
        }

        @Test
        void anUnverifiedCallerCannotWrite() throws Exception {
            assertThrows(ForbiddenException.class,
                    () -> interceptor.enforceVerifiedEmail(handler(WriteHandler.class), caller(false)));
        }

        @Test
        void aVerifiedCallerCanWrite() throws Exception {
            assertDoesNotThrow(
                    () -> interceptor.enforceVerifiedEmail(handler(WriteHandler.class), caller(true)));
        }

        @Test
        void anUnverifiedCallerCanStillRead() throws Exception {
            // The dashboard has to load in order to say "check your mail", and every screen that
            // says so is a read.
            assertDoesNotThrow(
                    () -> interceptor.enforceVerifiedEmail(handler(ReadHandler.class), caller(false)));
        }

        @Test
        void anApiKeyIsNotSubjectToTheGate() throws Exception {
            // A key can only exist because a verified user created one - creating it is a write.
            // Re-asking here would mean loading the owning user on every ingest call to answer a
            // question that was already answered at issue time.
            assertDoesNotThrow(() -> interceptor.enforceVerifiedEmail(handler(WriteHandler.class),
                    apiKey(ApiKeyScope.READ_WRITE)));
        }

        @Test
        void anUnauthenticatedRequestIsNotThisCheckSProblem() throws Exception {
            // enforceAccessLevel already refuses a caller with no membership role; this one must
            // not turn that into a different, more confusing 403.
            assertDoesNotThrow(() -> interceptor.enforceVerifiedEmail(handler(WriteHandler.class), null));
        }
    }
}
