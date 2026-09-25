package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.exception.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

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
}
