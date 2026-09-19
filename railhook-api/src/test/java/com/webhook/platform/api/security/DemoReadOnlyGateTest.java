package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.exception.DemoReadOnlyException;
import com.webhook.platform.common.demo.DemoTenant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The demo's read-only rule, as the interceptor applies it. {@code DemoSessionIntegrationTest}
 * walks every real handler with a real demo session; this pins who counts as the demo and what
 * the two annotations do.
 */
class DemoReadOnlyGateTest {

    private final ScopeEnforcementInterceptor interceptor = new ScopeEnforcementInterceptor(org -> Optional.empty());

    static class Handlers {
        public void write() {
        }

        @AllowedInDemo(reason = "test")
        public void allowed() {
        }

        @RefusedInDemo
        public void export() {
        }
    }

    private static HandlerMethod handler(String name) throws Exception {
        return new HandlerMethod(new Handlers(), Handlers.class.getDeclaredMethod(name));
    }

    private static MockHttpServletRequest request(String method) {
        return new MockHttpServletRequest(method, "/api/v1/anything");
    }

    private static JwtAuthenticationToken demoSession() {
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
        ApiKeyAuthenticationToken apiKey = new ApiKeyAuthenticationToken("k", DemoTenant.PROJECT_ID,
                DemoTenant.ORGANIZATION_ID, ApiKeyScope.READ_WRITE, Collections.emptyList());
        PortalSessionAuthenticationToken portal = new PortalSessionAuthenticationToken(UUID.randomUUID(),
                DemoTenant.ORGANIZATION_ID, DemoTenant.PROJECT_ID, UUID.randomUUID());

        HandlerMethod write = handler("write");
        assertThrows(DemoReadOnlyException.class, () -> interceptor.enforceDemoReadOnly(request("POST"), write, ownerWithoutClaim));
        assertThrows(DemoReadOnlyException.class, () -> interceptor.enforceDemoReadOnly(request("POST"), write, apiKey));
        assertThrows(DemoReadOnlyException.class, () -> interceptor.enforceDemoReadOnly(request("POST"), write, portal));
    }

    @Test
    void everyoneElseIsUntouched() throws Exception {
        JwtAuthenticationToken owner = new JwtAuthenticationToken(UUID.randomUUID(), UUID.randomUUID(),
                MembershipRole.OWNER, true, Collections.emptyList());
        assertDoesNotThrow(() -> interceptor.enforceDemoReadOnly(request("POST"), handler("write"), owner));
        assertDoesNotThrow(() -> interceptor.enforceDemoReadOnly(request("GET"), handler("export"), owner));
        assertDoesNotThrow(() -> interceptor.enforceDemoReadOnly(request("POST"), handler("write"), null));
    }
}
