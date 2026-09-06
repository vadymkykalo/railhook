package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.exception.ForbiddenException;

public class RbacUtil {

    public static void requireWriteAccess(MembershipRole role, ApiKeyScope apiKeyScope) {
        if (role == MembershipRole.VIEWER) {
            throw new ForbiddenException("Viewers have read-only access");
        }
        
        // API Key scope enforcement
        if (role == MembershipRole.API_KEY && apiKeyScope == ApiKeyScope.READ_ONLY) {
            throw new ForbiddenException("API key has read-only access. Write operations are not permitted.");
        }
        
        // DEVELOPER, OWNER, and READ_WRITE API keys have write access
    }

    public static void requireOwnerAccess(MembershipRole role) {
        if (role != MembershipRole.OWNER) {
            throw new ForbiddenException("Only owners can perform this action");
        }
    }
}
