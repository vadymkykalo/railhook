package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.webhook.platform.api.domain.entity.DeviceAuthCode;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.DeviceAuthStatus;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.enums.SessionClient;
import com.webhook.platform.api.domain.repository.DeviceAuthCodeRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.DeviceCodeResponse;
import com.webhook.platform.api.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceAuthServiceTest {

    private final UUID tenantOrgId = UUID.randomUUID();

    @Mock
    private DeviceAuthCodeRepository deviceAuthCodeRepository;

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSessionService userSessionService;

    @Mock
    private JwtUtil jwtUtil;

    private static final SessionOrigin CLI_ORIGIN =
            SessionOrigin.of(SessionClient.CLI, "railhook-cli/2.9.1", "203.0.113.7");

    @InjectMocks
    private DeviceAuthService deviceAuthService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(deviceAuthService, "appBaseUrl", "http://localhost:5173");
    }

    private void stubSessionTokenReads() {
        when(jwtUtil.getJtiFromToken(anyString())).thenReturn(UUID.randomUUID().toString());
        when(jwtUtil.getExpirationFromToken(anyString()))
                .thenReturn(Date.from(Instant.now().plus(1, ChronoUnit.DAYS)));
    }

    @BeforeEach
    void enterTenantScope() {
        TenantContext.set(tenantOrgId);
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    @Test
    void shouldInitiateDeviceAuth() {
        when(deviceAuthCodeRepository.save(any(DeviceAuthCode.class)))
                .thenAnswer(inv -> {
                    DeviceAuthCode code = inv.getArgument(0);
                    code.setId(UUID.randomUUID());
                    return code;
                });

        DeviceCodeResponse response = deviceAuthService.initiateDeviceAuth();

        assertNotNull(response);
        assertNotNull(response.getDeviceCode());
        assertNotNull(response.getUserCode());
        assertTrue(response.getVerificationUrl().contains("localhost:5173"));
        assertTrue(response.getVerificationUrl().contains(response.getUserCode()));
        assertTrue(response.getExpiresInSeconds() > 0);
        assertTrue(response.getPollIntervalSeconds() > 0);
        assertNotNull(response.getExpiresAt());

        verify(deviceAuthCodeRepository).save(any(DeviceAuthCode.class));
    }

    @Test
    void shouldApproveDeviceCode() {
        UUID userId = UUID.randomUUID();
        String userCode = "ABCD-1234";

        DeviceAuthCode code = DeviceAuthCode.builder()
                .id(UUID.randomUUID())
                .deviceCode("dev-code-123")
                .userCode(userCode)
                .status(DeviceAuthStatus.PENDING)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        when(deviceAuthCodeRepository.findByUserCodeAndStatus(userCode, DeviceAuthStatus.PENDING))
                .thenReturn(Optional.of(code));
        when(deviceAuthCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deviceAuthService.approveDeviceCode(userCode, userId);

        ArgumentCaptor<DeviceAuthCode> captor = ArgumentCaptor.forClass(DeviceAuthCode.class);
        verify(deviceAuthCodeRepository).save(captor.capture());
        assertEquals(DeviceAuthStatus.APPROVED, captor.getValue().getStatus());
        assertEquals(userId, captor.getValue().getUserId());
        assertEquals(tenantOrgId, captor.getValue().getOrganizationId());
        assertNotNull(captor.getValue().getApprovedAt());
    }

    @Test
    void shouldThrowWhenApprovingExpiredCode() {
        String userCode = "EXPD-0001";
        DeviceAuthCode code = DeviceAuthCode.builder()
                .id(UUID.randomUUID())
                .userCode(userCode)
                .status(DeviceAuthStatus.PENDING)
                .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .build();

        when(deviceAuthCodeRepository.findByUserCodeAndStatus(userCode, DeviceAuthStatus.PENDING))
                .thenReturn(Optional.of(code));
        when(deviceAuthCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(ResponseStatusException.class, () ->
                deviceAuthService.approveDeviceCode(userCode, UUID.randomUUID()));
    }

    @Test
    void aDeniedCodeNeverBecomesATokenEvenIfItsUserIdIsSet() {
        String userCode = "DENY-0002";
        UUID userId = UUID.randomUUID();
        DeviceAuthCode code = DeviceAuthCode.builder()
                .id(UUID.randomUUID())
                .userCode(userCode)
                .status(DeviceAuthStatus.PENDING)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        when(deviceAuthCodeRepository.findByUserCodeAndStatus(userCode, DeviceAuthStatus.PENDING))
                .thenReturn(Optional.of(code));
        when(deviceAuthCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deviceAuthService.denyDeviceCode(userCode, userId);

        assertEquals(DeviceAuthStatus.DENIED, code.getStatus());
        assertNull(code.getApprovedAt());
        assertNull(code.getOrganizationId());
    }

    @Test
    void shouldRefusePollingADeniedCode() {
        String deviceCode = "dev-denied";
        DeviceAuthCode code = DeviceAuthCode.builder()
                .id(UUID.randomUUID())
                .deviceCode(deviceCode)
                .userCode("DENY-0003")
                .status(DeviceAuthStatus.DENIED)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        when(deviceAuthCodeRepository.findByDeviceCode(deviceCode)).thenReturn(Optional.of(code));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                deviceAuthService.pollDeviceToken(deviceCode, CLI_ORIGIN));
        assertEquals(403, thrown.getStatusCode().value());
        verify(deviceAuthCodeRepository, never()).markConsumedIfApproved(any());
    }

    @Test
    void shouldReturnTokensWhenPollingApprovedCode() {
        UUID userId = UUID.randomUUID();
        String deviceCode = "dev-approved";
        UUID codeId = UUID.randomUUID();

        DeviceAuthCode code = DeviceAuthCode.builder()
                .id(codeId)
                .deviceCode(deviceCode)
                .userCode("APPR-0001")
                .status(DeviceAuthStatus.APPROVED)
                .userId(userId)
                .organizationId(tenantOrgId)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        Membership membership = new Membership();
        membership.setRole(MembershipRole.OWNER);

        when(deviceAuthCodeRepository.findByDeviceCode(deviceCode)).thenReturn(Optional.of(code));
        when(membershipRepository.findByUserIdAndOrganizationId(userId, tenantOrgId)).thenReturn(Optional.of(membership));
        when(deviceAuthCodeRepository.markConsumedIfApproved(codeId)).thenReturn(1);
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(User.builder().id(userId).emailVerified(true).build()));
        when(jwtUtil.generateAccessToken(eq(userId), eq(tenantOrgId), eq(MembershipRole.OWNER), any(), eq(true)))
                .thenReturn("access-token-123");
        when(jwtUtil.generateRefreshToken(eq(userId), any())).thenReturn("refresh-token-456");
        stubSessionTokenReads();

        AuthResponse response = deviceAuthService.pollDeviceToken(deviceCode, CLI_ORIGIN);

        assertNotNull(response);
        assertEquals("access-token-123", response.getAccessToken());
        assertEquals("refresh-token-456", response.getRefreshToken());
        verify(deviceAuthCodeRepository).markConsumedIfApproved(codeId);
    }

    @Test
    void shouldRefuseTokensWhenTheMembershipIsSuspended() {
        // Suspended between approving and polling: the exchange once still minted a token pair.
        UUID userId = UUID.randomUUID();
        String deviceCode = "dev-suspended";
        UUID codeId = UUID.randomUUID();

        DeviceAuthCode code = DeviceAuthCode.builder()
                .id(codeId)
                .deviceCode(deviceCode)
                .userCode("SUSP-0001")
                .status(DeviceAuthStatus.APPROVED)
                .userId(userId)
                .organizationId(tenantOrgId)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        Membership membership = new Membership();
        membership.setRole(MembershipRole.OWNER);
        membership.setStatus(MembershipStatus.DISABLED);

        when(deviceAuthCodeRepository.findByDeviceCode(deviceCode)).thenReturn(Optional.of(code));
        when(membershipRepository.findByUserIdAndOrganizationId(userId, tenantOrgId))
                .thenReturn(Optional.of(membership));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> deviceAuthService.pollDeviceToken(deviceCode, CLI_ORIGIN));
        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());

        verify(deviceAuthCodeRepository, never()).markConsumedIfApproved(any());
        verify(jwtUtil, never()).generateAccessToken(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void shouldUseRoleFromApprovedOrgMembershipNotAnArbitraryOne() {
        // The token once carried whichever membership findByUserId() returned first.
        UUID userId = UUID.randomUUID();
        UUID ownOrgId = UUID.randomUUID();
        UUID clientOrgId = UUID.randomUUID();
        String deviceCode = "dev-multi-org";
        UUID codeId = UUID.randomUUID();

        DeviceAuthCode code = DeviceAuthCode.builder()
                .id(codeId)
                .deviceCode(deviceCode)
                .userCode("MORG-0001")
                .status(DeviceAuthStatus.APPROVED)
                .userId(userId)
                .organizationId(clientOrgId)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        Membership ownOrgMembership = new Membership();
        ownOrgMembership.setRole(MembershipRole.OWNER);
        Membership clientOrgMembership = new Membership();
        clientOrgMembership.setRole(MembershipRole.VIEWER);

        when(deviceAuthCodeRepository.findByDeviceCode(deviceCode)).thenReturn(Optional.of(code));
        when(membershipRepository.findByUserIdAndOrganizationId(userId, clientOrgId))
                .thenReturn(Optional.of(clientOrgMembership));
        when(deviceAuthCodeRepository.markConsumedIfApproved(codeId)).thenReturn(1);
        when(jwtUtil.generateAccessToken(eq(userId), eq(clientOrgId), eq(MembershipRole.VIEWER), any(), anyBoolean()))
                .thenReturn("viewer-token");
        when(jwtUtil.generateRefreshToken(eq(userId), any())).thenReturn("refresh-token");
        stubSessionTokenReads();

        AuthResponse response = deviceAuthService.pollDeviceToken(deviceCode, CLI_ORIGIN);

        assertEquals("viewer-token", response.getAccessToken());
        verify(jwtUtil).generateAccessToken(eq(userId), eq(clientOrgId), eq(MembershipRole.VIEWER), any(), anyBoolean());
        verify(jwtUtil, never()).generateAccessToken(eq(userId), eq(ownOrgId), any(), any(), anyBoolean());
        verify(membershipRepository, never()).findByUserId(any());
    }

    @Test
    void shouldFailClosedWhenUserHasNoMembershipInApprovedOrg() {
        UUID userId = UUID.randomUUID();
        String deviceCode = "dev-no-membership";

        DeviceAuthCode code = DeviceAuthCode.builder()
                .id(UUID.randomUUID())
                .deviceCode(deviceCode)
                .userCode("NOMB-0001")
                .status(DeviceAuthStatus.APPROVED)
                .userId(userId)
                .organizationId(tenantOrgId)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        when(deviceAuthCodeRepository.findByDeviceCode(deviceCode)).thenReturn(Optional.of(code));
        when(membershipRepository.findByUserIdAndOrganizationId(userId, tenantOrgId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                deviceAuthService.pollDeviceToken(deviceCode, CLI_ORIGIN));
        assertEquals(403, ex.getStatusCode().value());
        verify(deviceAuthCodeRepository, never()).markConsumedIfApproved(any());
        verify(jwtUtil, never()).generateAccessToken(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void shouldFailWhenPollingAlreadyConsumedCode() {
        String deviceCode = "dev-consumed";
        DeviceAuthCode code = DeviceAuthCode.builder()
                .id(UUID.randomUUID())
                .deviceCode(deviceCode)
                .status(DeviceAuthStatus.CONSUMED)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        when(deviceAuthCodeRepository.findByDeviceCode(deviceCode)).thenReturn(Optional.of(code));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                deviceAuthService.pollDeviceToken(deviceCode, CLI_ORIGIN));
        assertEquals(410, ex.getStatusCode().value());
        verify(membershipRepository, never()).findByUserIdAndOrganizationId(any(), any());
    }

    @Test
    void shouldFailWhenLosingTheConsumeRace() {
        // A concurrent second poll already flipped the row to CONSUMED.
        UUID userId = UUID.randomUUID();
        String deviceCode = "dev-race";
        UUID codeId = UUID.randomUUID();

        DeviceAuthCode code = DeviceAuthCode.builder()
                .id(codeId)
                .deviceCode(deviceCode)
                .status(DeviceAuthStatus.APPROVED)
                .userId(userId)
                .organizationId(tenantOrgId)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        Membership membership = new Membership();
        membership.setRole(MembershipRole.DEVELOPER);

        when(deviceAuthCodeRepository.findByDeviceCode(deviceCode)).thenReturn(Optional.of(code));
        when(membershipRepository.findByUserIdAndOrganizationId(userId, tenantOrgId)).thenReturn(Optional.of(membership));
        when(deviceAuthCodeRepository.markConsumedIfApproved(codeId)).thenReturn(0);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                deviceAuthService.pollDeviceToken(deviceCode, CLI_ORIGIN));
        assertEquals(410, ex.getStatusCode().value());
        verify(jwtUtil, never()).generateAccessToken(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void shouldThrow202WhenPollingPendingCode() {
        String deviceCode = "dev-pending";
        DeviceAuthCode code = DeviceAuthCode.builder()
                .id(UUID.randomUUID())
                .deviceCode(deviceCode)
                .status(DeviceAuthStatus.PENDING)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        when(deviceAuthCodeRepository.findByDeviceCode(deviceCode)).thenReturn(Optional.of(code));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                deviceAuthService.pollDeviceToken(deviceCode, CLI_ORIGIN));
        assertEquals(202, ex.getStatusCode().value());
    }

    @Test
    void shouldThrow410WhenPollingExpiredCode() {
        String deviceCode = "dev-expired";
        DeviceAuthCode code = DeviceAuthCode.builder()
                .id(UUID.randomUUID())
                .deviceCode(deviceCode)
                .status(DeviceAuthStatus.PENDING)
                .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .build();

        when(deviceAuthCodeRepository.findByDeviceCode(deviceCode)).thenReturn(Optional.of(code));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                deviceAuthService.pollDeviceToken(deviceCode, CLI_ORIGIN));
        assertEquals(410, ex.getStatusCode().value());
    }
}
