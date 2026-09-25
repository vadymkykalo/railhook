package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.exception.ForbiddenException;

import java.util.UUID;

/** For an API key, userId is null and apiKeyProjectId and apiKeyScope are set; for a JWT, the reverse. */
public record AuthContext(
        UUID userId,
        UUID organizationId,
        MembershipRole role,
        UUID apiKeyProjectId,
        ApiKeyScope apiKeyScope
) {

    public void requireWriteAccess() {
        RbacUtil.requireWriteAccess(role, apiKeyScope);
    }

    public void requireOwnerAccess() {
        RbacUtil.requireOwnerAccess(role);
    }

    /** A no-op for a JWT, which is scoped by organization membership instead. */
    public void validateProjectAccess(UUID requestedProjectId) {
        if (apiKeyProjectId != null && !apiKeyProjectId.equals(requestedProjectId)) {
            throw new ForbiddenException("API key does not have access to this project");
        }
    }

    public boolean isApiKey() {
        return role == MembershipRole.API_KEY;
    }

    public UUID requireUserId() {
        if (userId == null) {
            throw new ForbiddenException("This operation requires user authentication (JWT). API keys are not supported.");
        }
        return userId;
    }

    public void requireJwt() {
        if (isApiKey()) {
            throw new ForbiddenException("This endpoint requires user authentication (JWT). API keys are not permitted.");
        }
    }
}
