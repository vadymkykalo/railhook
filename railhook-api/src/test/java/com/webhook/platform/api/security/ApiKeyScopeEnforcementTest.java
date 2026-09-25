package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.exception.ForbiddenException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiKeyScopeEnforcementTest {

    @ParameterizedTest
    @CsvSource({
            "API_KEY,   READ_ONLY,  false",
            "API_KEY,   READ_WRITE, true",
            "OWNER,     ,           true",
            "DEVELOPER, ,           true",
            "VIEWER,    ,           false",
    })
    void writeAccessFollowsTheKeysScopeOrTheMembersRole(MembershipRole role, ApiKeyScope scope, boolean allowed) {
        boolean apiKey = role == MembershipRole.API_KEY;
        AuthContext context = new AuthContext(
                apiKey ? null : UUID.randomUUID(),
                UUID.randomUUID(),
                role,
                apiKey ? UUID.randomUUID() : null,
                scope);

        if (allowed) {
            assertDoesNotThrow(context::requireWriteAccess);
        } else {
            assertThrows(ForbiddenException.class, context::requireWriteAccess);
        }
    }
}
