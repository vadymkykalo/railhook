package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.exception.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
class VerificationGateTest {

    private final ScopeEnforcementInterceptor interceptor = new ScopeEnforcementInterceptor(org -> java.util.Optional.empty());

    @RequireAccess(AccessLevel.WRITE)
    static class WriteHandler {
        public void handle() {
        }
    }

    static class ReadHandler {
        public void handle() {
        }
    }

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
        ApiKeyAuthenticationToken apiKey = new ApiKeyAuthenticationToken("k", UUID.randomUUID(),
                UUID.randomUUID(), com.webhook.platform.api.domain.enums.ApiKeyScope.READ_WRITE,
                Collections.emptyList());
        assertDoesNotThrow(() -> interceptor.enforceVerifiedEmail(handler(WriteHandler.class), apiKey));
    }

    @Test
    void anUnauthenticatedRequestIsNotThisCheckSProblem() throws Exception {
        // enforceAccessLevel already refuses a caller with no membership role; this one must
        // not turn that into a different, more confusing 403.
        assertDoesNotThrow(() -> interceptor.enforceVerifiedEmail(handler(WriteHandler.class), null));
    }
}
