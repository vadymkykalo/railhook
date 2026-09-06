package com.webhook.platform.api.security;

import com.webhook.platform.api.controller.EndpointController;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.exception.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ApiKeyScopeEnforcementTest {

    @Test
    void testReadOnlyApiKeyBlockedFromWrite() {
        // Given: READ_ONLY API key in AuthContext
        AuthContext readOnlyContext = new AuthContext(
                null,
                UUID.randomUUID(),
                MembershipRole.API_KEY,
                UUID.randomUUID(),
                ApiKeyScope.READ_ONLY
        );

        // When/Then: requireWriteAccess should throw ForbiddenException
        ForbiddenException exception = assertThrows(ForbiddenException.class,
                readOnlyContext::requireWriteAccess);
        
        assertTrue(exception.getMessage().contains("read-only access"));
    }

    @Test
    void testReadWriteApiKeyAllowedForWrite() {
        // Given: READ_WRITE API key in AuthContext
        AuthContext readWriteContext = new AuthContext(
                null,
                UUID.randomUUID(),
                MembershipRole.API_KEY,
                UUID.randomUUID(),
                ApiKeyScope.READ_WRITE
        );

        // When/Then: requireWriteAccess should NOT throw
        assertDoesNotThrow(readWriteContext::requireWriteAccess);
    }

    @Test
    void testJwtUserWithDeveloperRoleAllowedForWrite() {
        // Given: JWT user with DEVELOPER role
        AuthContext developerContext = new AuthContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MembershipRole.DEVELOPER,
                null,
                null
        );

        // When/Then: requireWriteAccess should NOT throw
        assertDoesNotThrow(developerContext::requireWriteAccess);
    }

    @Test
    void testJwtUserWithOwnerRoleAllowedForWrite() {
        // Given: JWT user with OWNER role
        AuthContext ownerContext = new AuthContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MembershipRole.OWNER,
                null,
                null
        );

        // When/Then: requireWriteAccess should NOT throw
        assertDoesNotThrow(ownerContext::requireWriteAccess);
    }

    @Test
    void testJwtUserWithViewerRoleBlockedFromWrite() {
        // Given: JWT user with VIEWER role
        AuthContext viewerContext = new AuthContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MembershipRole.VIEWER,
                null,
                null
        );

        // When/Then: requireWriteAccess should throw ForbiddenException
        ForbiddenException exception = assertThrows(ForbiddenException.class,
                viewerContext::requireWriteAccess);
        
        assertTrue(exception.getMessage().contains("read-only"));
    }

    @Test
    void testRbacUtilDirectlyWithReadOnlyScope() {
        // When/Then: Direct call to RbacUtil with READ_ONLY scope should throw
        ForbiddenException exception = assertThrows(ForbiddenException.class,
                () -> RbacUtil.requireWriteAccess(MembershipRole.API_KEY, ApiKeyScope.READ_ONLY));
        
        assertTrue(exception.getMessage().contains("read-only access"));
    }

    @Test
    void testRbacUtilDirectlyWithReadWriteScope() {
        // When/Then: Direct call to RbacUtil with READ_WRITE scope should NOT throw
        assertDoesNotThrow(
                () -> RbacUtil.requireWriteAccess(MembershipRole.API_KEY, ApiKeyScope.READ_WRITE));
    }

    @Test
    void testRbacUtilWithNullScope() {
        // When/Then: null scope (JWT users) should NOT throw for DEVELOPER
        assertDoesNotThrow(
                () -> RbacUtil.requireWriteAccess(MembershipRole.DEVELOPER, null));
    }

    // ── EndpointController.rotateSecret cross-project reproduction ──
    //
    // Worst concrete case: EndpointController.rotateSecret
    // never called AuthContext.validateProjectAccess, and EndpointService
    // .rotateSecret checks only organizationId — which AuthContext derives from
    // the API key's own project, so it's the same for every project in an org.
    // A key scoped to "staging" could rotate a "production" endpoint's signing
    // secret and get it back in plaintext. AuthContext-level unit tests above
    // can't reproduce this: the bug was that the handler never called
    // validateProjectAccess at all, not that the method behaves wrong. The fix
    // moved the check into ScopeEnforcementInterceptor, which now runs
    // unconditionally against the actual EndpointController.rotateSecret
    // HandlerMethod, independent of what the handler itself does.
    //
    // This exercises ScopeEnforcementInterceptor.preHandle() directly against
    // that exact method via reflection — no Spring context / Docker required.
    // (A full HTTP-level reproduction, including asserting the plaintext
    // secret is actually returned to the legitimate owner, lives in
    // ProjectScopeEnforcementIsolationTest#apiKey_rotateSecret_*.)

    @Test
    void testRotateSecretHandler_apiKeyScopedToDifferentProject_blockedByInterceptor() throws NoSuchMethodException {
        UUID keyProjectId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();

        HandlerMethod rotateSecretHandler = rotateSecretHandlerMethod();
        MockHttpServletRequest request = rotateSecretRequest(otherProjectId);
        authenticateAsApiKey(keyProjectId);

        try {
            ScopeEnforcementInterceptor interceptor = new ScopeEnforcementInterceptor(org -> java.util.Optional.empty());
            ForbiddenException ex = assertThrows(ForbiddenException.class,
                    () -> interceptor.preHandle(request, new MockHttpServletResponse(), rotateSecretHandler));
            assertTrue(ex.getMessage().contains("does not have access to this project"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void testRotateSecretHandler_apiKeyScopedToOwnProject_allowedByInterceptor() throws NoSuchMethodException {
        UUID projectId = UUID.randomUUID();

        HandlerMethod rotateSecretHandler = rotateSecretHandlerMethod();
        MockHttpServletRequest request = rotateSecretRequest(projectId);
        authenticateAsApiKey(projectId);

        try {
            ScopeEnforcementInterceptor interceptor = new ScopeEnforcementInterceptor(org -> java.util.Optional.empty());
            assertDoesNotThrow(
                    () -> interceptor.preHandle(request, new MockHttpServletResponse(), rotateSecretHandler));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static HandlerMethod rotateSecretHandlerMethod() throws NoSuchMethodException {
        Method method = EndpointController.class.getMethod("rotateSecret", UUID.class, AuthContext.class);
        return new HandlerMethod(mock(EndpointController.class), method);
    }

    private static MockHttpServletRequest rotateSecretRequest(UUID pathProjectId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/v1/projects/" + pathProjectId + "/endpoints/" + UUID.randomUUID() + "/rotate-secret");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("projectId", pathProjectId.toString()));
        return request;
    }

    private static void authenticateAsApiKey(UUID keyProjectId) {
        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthenticationToken("test-key", keyProjectId, UUID.randomUUID(), ApiKeyScope.READ_WRITE, List.of()));
    }
}
