package com.webhook.platform.api.audit;

import com.webhook.platform.api.domain.entity.AuditLog;
import com.webhook.platform.api.domain.repository.AuditLogRepository;
import com.webhook.platform.api.security.ApiKeyAuthenticationToken;
import com.webhook.platform.api.security.JwtAuthenticationToken;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which organization an audit row is stamped with, per caller type.
 *
 * <p>This exists because the API-key branch resolved the organization by reflecting over method
 * parameter names looking for one called {@code organizationId} — and structural tenancy removed that
 * parameter from every service method, enforced by {@code ServiceTenantParameterTest}. The lookup
 * therefore returned null for every audited action but one, and those rows were written under the
 * SYSTEM sentinel instead of the key's organization. Nothing failed: the row was still written,
 * just under the wrong tenant, and a tenant-scoped reader simply never saw it.
 *
 * <p>Deliberately a plain {@code *Test}: mocks only, no Spring context and no container, so it
 * must run in the no-Docker unit job (see {@code scripts/check-test-routing.sh}).
 */
class AuditLogAspectTest {

    private static final UUID KEY_ORG = UUID.randomUUID();
    private static final UUID JWT_ORG = UUID.randomUUID();
    private static final UUID JWT_USER = UUID.randomUUID();

    private AuditLogRepository auditLogRepository;
    private AuditLogAspect aspect;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        aspect = new AuditLogAspect(auditLogRepository, mock(TrustedProxyResolver.class));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        aspect.shutdown();
    }

    @Test
    void apiKeyCaller_stampsTheKeysOrganization() throws Throwable {
        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthenticationToken("key", UUID.randomUUID(), KEY_ORG, ApiKeyScope.READ_WRITE, List.of()));

        aspect.audit(joinPoint(new String[]{"endpointId"}, new Object[]{UUID.randomUUID()}),
                auditable(AuditAction.CREATE, "Endpoint"));

        assertThat(savedRow().getOrganizationId()).isEqualTo(KEY_ORG);
    }

    @Test
    void jwtCaller_stampsTheTokensOrganization() throws Throwable {
        JwtAuthenticationToken jwt = mock(JwtAuthenticationToken.class);
        when(jwt.getUserId()).thenReturn(JWT_USER);
        when(jwt.getOrganizationId()).thenReturn(JWT_ORG);
        SecurityContextHolder.getContext().setAuthentication(jwt);

        aspect.audit(joinPoint(new String[]{"endpointId"}, new Object[]{UUID.randomUUID()}),
                auditable(AuditAction.CREATE, "Endpoint"));

        AuditLog row = savedRow();
        assertThat(row.getOrganizationId()).isEqualTo(JWT_ORG);
        assertThat(row.getUserId()).isEqualTo(JWT_USER);
    }

    /**
     * The one audited method that still takes the organization as a parameter is
     * {@code MembershipService.acceptInvite}: it is {@code @SystemTenant} because the accepting
     * user's ambient tenant is a different organization, so neither the token nor
     * {@code TenantContext} names the organization the invite belongs to.
     */
    @Test
    void organizationIdParameter_survivesAsTheSourceForInviteAcceptance() throws Throwable {
        UUID inviteOrg = UUID.randomUUID();
        JwtAuthenticationToken jwt = mock(JwtAuthenticationToken.class);
        when(jwt.getUserId()).thenReturn(JWT_USER);
        when(jwt.getOrganizationId()).thenReturn(null);
        SecurityContextHolder.getContext().setAuthentication(jwt);

        aspect.audit(joinPoint(new String[]{"organizationId", "inviteToken"}, new Object[]{inviteOrg, "tok"}),
                auditable(AuditAction.INVITE_ACCEPTED, "Member"));

        assertThat(savedRow().getOrganizationId()).isEqualTo(inviteOrg);
    }

    @Test
    void unauthenticatedAction_isWrittenUnderTheSystemSentinel() throws Throwable {
        aspect.audit(joinPoint(new String[]{"email"}, new Object[]{"someone@example.com"}),
                auditable(AuditAction.LOGIN, "User"));

        // Login, register and password reset have no organization, and the row says so with the
        // sentinel rather than leaving the field unset for Hibernate's @TenantId generator to fill
        // in. Same value on the row either way; this way the code states it.
        assertThat(savedRow().getOrganizationId()).isEqualTo(TenantContext.SYSTEM);
    }

    private AuditLog savedRow() {
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        // The write is handed to this aspect's own single-thread executor.
        verify(auditLogRepository, timeout(2_000)).save(saved.capture());
        return saved.getValue();
    }

    private ProceedingJoinPoint joinPoint(String[] parameterNames, Object[] args) throws Throwable {
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getParameterNames()).thenReturn(parameterNames);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.proceed()).thenReturn(null);
        return joinPoint;
    }

    private Auditable auditable(AuditAction action, String resourceType) {
        Auditable auditable = mock(Auditable.class);
        when(auditable.action()).thenReturn(action);
        when(auditable.resourceType()).thenReturn(resourceType);
        return auditable;
    }
}
