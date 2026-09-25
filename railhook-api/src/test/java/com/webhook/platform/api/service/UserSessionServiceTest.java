package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.UserSession;
import com.webhook.platform.api.domain.enums.SessionClient;
import com.webhook.platform.api.domain.repository.UserSessionRepository;
import com.webhook.platform.api.dto.SessionResponse;
import com.webhook.platform.api.exception.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// user_sessions has no @TenantId, so every lookup must be by (session, user).
@ExtendWith(MockitoExtension.class)
@DisplayName("UserSessionService — the list of signed-in devices, and ending one")
class UserSessionServiceTest {

    @Mock private UserSessionRepository userSessionRepository;
    @Mock private TokenBlacklistService tokenBlacklistService;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private UserSessionService service() {
        return new UserSessionService(userSessionRepository, tokenBlacklistService);
    }

    private UserSession session(UUID id, UUID owner, SessionClient client) {
        return UserSession.builder()
                .id(id)
                .userId(owner)
                .organizationId(UUID.randomUUID())
                .refreshTokenJti(UUID.randomUUID().toString())
                .client(client)
                .userAgent("Mozilla/5.0")
                .ipAddress("198.51.100.4")
                .createdAt(Instant.now().minusSeconds(3600))
                .lastSeenAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86_400))
                .build();
    }

    @Test
    @DisplayName("lists live sessions and flags the one making the request")
    void listsAndFlagsCurrent() {
        UserSession current = session(sessionId, userId, SessionClient.WEB);
        UserSession cli = session(UUID.randomUUID(), userId, SessionClient.CLI);
        when(userSessionRepository
                .findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(eq(userId), any()))
                .thenReturn(List.of(current, cli));

        List<SessionResponse> sessions = service().listSessions(userId, sessionId);

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).isCurrent()).isTrue();
        assertThat(sessions.get(1).isCurrent()).isFalse();
        assertThat(sessions.get(1).getClient())
                .as("a CLI grant is the one people are most surprised to still have, so it is named")
                .isEqualTo(SessionClient.CLI);
    }

    @Test
    @DisplayName("the list carries no token material of any kind")
    void listLeaksNoCredential() {
        UserSession live = session(sessionId, userId, SessionClient.WEB);
        when(userSessionRepository
                .findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(eq(userId), any()))
                .thenReturn(List.of(live));

        SessionResponse response = service().listSessions(userId, null).get(0);

        assertThat(response.toString()).doesNotContain(live.getRefreshTokenJti());
    }

    @Test
    @DisplayName("revoking marks the row and kills the access token the session already issued")
    void revokeReachesBothHalves() {
        UserSession live = session(sessionId, userId, SessionClient.WEB);
        when(userSessionRepository.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.of(live));

        service().revokeSession(userId, sessionId);

        assertThat(live.getRevokedAt()).isNotNull();
        verify(userSessionRepository).save(live);
        verify(tokenBlacklistService).revokeSession(sessionId, Date.from(live.getExpiresAt()));
    }

    @Test
    @DisplayName("a session belonging to someone else is a 404, not a revocation")
    void cannotRevokeAStrangersSession() {
        when(userSessionRepository.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().revokeSession(userId, sessionId))
                .isInstanceOf(NotFoundException.class);

        verify(tokenBlacklistService, never()).revokeSession(any(), any());
    }

    @Test
    @DisplayName("revoking twice is the same as revoking once")
    void revokeIsIdempotent() {
        UserSession alreadyRevoked = session(sessionId, userId, SessionClient.WEB);
        Instant revokedAt = Instant.now().minusSeconds(30);
        alreadyRevoked.setRevokedAt(revokedAt);
        when(userSessionRepository.findByIdAndUserId(sessionId, userId))
                .thenReturn(Optional.of(alreadyRevoked));

        service().revokeSession(userId, sessionId);

        assertThat(alreadyRevoked.getRevokedAt()).isEqualTo(revokedAt);
        verify(userSessionRepository, never()).save(any());
        // The Redis marker has a TTL, so re-revoking must rewrite it.
        verify(tokenBlacklistService).revokeSession(eq(sessionId), any());
    }

    @Test
    @DisplayName("sign out everywhere uses the epoch, which is one write for every token at once")
    void signOutEverywhere() {
        when(userSessionRepository.revokeAllForUser(eq(userId), any())).thenReturn(3);

        assertThat(service().revokeAllSessions(userId)).isEqualTo(3);

        verify(tokenBlacklistService).revokeAllUserTokens(userId);
    }

    @Test
    @DisplayName("a live session is one that is neither revoked nor past its refresh token's expiry")
    void activeMeansBoth() {
        UserSession live = session(sessionId, userId, SessionClient.WEB);
        assertThat(live.isActive(Instant.now())).isTrue();

        live.setRevokedAt(Instant.now());
        assertThat(live.isActive(Instant.now())).isFalse();

        UserSession expired = session(sessionId, userId, SessionClient.WEB);
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        assertThat(expired.isActive(Instant.now())).isFalse();
    }
}
