package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.domain.entity.DeviceAuthCode;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.UserSession;
import com.webhook.platform.api.domain.enums.DeviceAuthStatus;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.enums.SessionClient;
import com.webhook.platform.api.domain.repository.DeviceAuthCodeRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.DeviceCodeResponse;
import com.webhook.platform.api.security.JwtUtil;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceAuthService {

    private final DeviceAuthCodeRepository deviceAuthCodeRepository;
    private final MembershipRepository membershipRepository;
    private final com.webhook.platform.api.domain.repository.UserRepository userRepository;
    private final UserSessionService userSessionService;
    private final JwtUtil jwtUtil;

    @Value("${app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int CODE_EXPIRY_MINUTES = 10;
    private static final int POLL_INTERVAL_SECONDS = 5;

    @SystemTenant("issues a device code before any user or organization is known -- device_auth_codes is deliberately not tenant-scoped for the same reason")
    @Transactional
    public DeviceCodeResponse initiateDeviceAuth() {
        String deviceCode = generateDeviceCode();
        String userCode = generateUserCode();
        Instant expiresAt = Instant.now().plus(CODE_EXPIRY_MINUTES, ChronoUnit.MINUTES);

        DeviceAuthCode code = DeviceAuthCode.builder()
                .deviceCode(deviceCode)
                .userCode(userCode)
                .status(DeviceAuthStatus.PENDING)
                .expiresAt(expiresAt)
                .build();

        deviceAuthCodeRepository.save(code);
        log.info("Device auth initiated: userCode={}", userCode);

        return DeviceCodeResponse.builder()
                .deviceCode(deviceCode)
                .userCode(userCode)
                .verificationUrl(appBaseUrl + "/device?code=" + userCode)
                .expiresInSeconds(CODE_EXPIRY_MINUTES * 60)
                .pollIntervalSeconds(POLL_INTERVAL_SECONDS)
                .expiresAt(expiresAt)
                .build();
    }

    @Transactional
    public void approveDeviceCode(String userCode, UUID userId) {
        UUID organizationId = TenantContext.require();
        DeviceAuthCode code = deviceAuthCodeRepository.findByUserCodeAndStatus(userCode, DeviceAuthStatus.PENDING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Device code not found or already used"));

        if (code.getExpiresAt().isBefore(Instant.now())) {
            code.setStatus(DeviceAuthStatus.EXPIRED);
            deviceAuthCodeRepository.save(code);
            throw new ResponseStatusException(HttpStatus.GONE, "Device code has expired");
        }

        code.setStatus(DeviceAuthStatus.APPROVED);
        code.setUserId(userId);
        code.setOrganizationId(organizationId);
        code.setApprovedAt(Instant.now());
        deviceAuthCodeRepository.save(code);

        log.info("Device auth approved: userCode={}, userId={}", userCode, userId);
    }

    // For a person who does not recognise the code, so whoever asked stops polling.
    @Transactional
    public void denyDeviceCode(String userCode, UUID userId) {
        DeviceAuthCode code = deviceAuthCodeRepository.findByUserCodeAndStatus(userCode, DeviceAuthStatus.PENDING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Device code not found or already used"));

        code.setStatus(DeviceAuthStatus.DENIED);
        deviceAuthCodeRepository.save(code);

        log.warn("Device auth denied: userCode={}, deniedBy={}", userCode, userId);
    }

    @SystemTenant("polled by an unauthenticated CLI; the membership read is what decides which organization the issued token names")
    @Transactional
    public AuthResponse pollDeviceToken(String deviceCode, SessionOrigin origin) {
        DeviceAuthCode code = deviceAuthCodeRepository.findByDeviceCode(deviceCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device code not found"));

        if (code.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Device code has expired");
        }

        switch (code.getStatus()) {
            case PENDING:
                throw new ResponseStatusException(HttpStatus.ACCEPTED, "authorization_pending");
            case DENIED:
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Device authorization was denied");
            case EXPIRED:
                throw new ResponseStatusException(HttpStatus.GONE, "Device code has expired");
            case CONSUMED:
                // Already exchanged by an earlier or racing poll. Fail closed rather than mint a second pair.
                throw new ResponseStatusException(HttpStatus.GONE, "Device code has already been used");
            case APPROVED:
                break;
        }

        // The role comes from the approved organization's membership, or a VIEWER approval could
        // mint an OWNER token from another org.
        Membership membership = membershipRepository
                .findByUserIdAndOrganizationId(code.getUserId(), code.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "User is not a member of the approved organization"));

        // A code approved just before a suspension would otherwise still hand out fresh access.
        if (membership.getStatus() == MembershipStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your membership in this organization has been suspended");
        }

        // Only the caller that flips APPROVED to CONSUMED mints tokens.
        int consumed = deviceAuthCodeRepository.markConsumedIfApproved(code.getId());
        if (consumed == 0) {
            throw new ResponseStatusException(HttpStatus.GONE, "Device code has already been used");
        }

        // A session named CLI, so the user can see and end the credential most likely to outlive its machine.
        UUID sessionId = UUID.randomUUID();
        String refreshToken = jwtUtil.generateRefreshToken(code.getUserId(), sessionId);

        userSessionService.open(UserSession.builder()
                .id(sessionId)
                .userId(code.getUserId())
                .organizationId(code.getOrganizationId())
                .refreshTokenJti(jwtUtil.getJtiFromToken(refreshToken))
                .client(SessionClient.CLI)
                .userAgent(origin.userAgent())
                .ipAddress(origin.ipAddress())
                .lastSeenAt(Instant.now())
                .expiresAt(jwtUtil.getExpirationFromToken(refreshToken).toInstant())
                .build());

        boolean emailVerified = userRepository.findById(code.getUserId())
                .map(u -> Boolean.TRUE.equals(u.getEmailVerified()))
                .orElse(false);

        String accessToken = jwtUtil.generateAccessToken(
                code.getUserId(), code.getOrganizationId(), membership.getRole(), sessionId, emailVerified);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    @SystemTenant
    @Scheduled(fixedDelayString = "${device-auth.cleanup-interval-ms:300000}")
    @Transactional
    public void cleanupExpiredCodes() {
        int expired = deviceAuthCodeRepository.expireOldCodes(Instant.now());
        if (expired > 0) {
            log.debug("Expired {} device auth codes", expired);
        }
    }

    private String generateDeviceCode() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateUserCode() {
        // No 0/O or 1/I, so the code is easy to type.
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            sb.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
