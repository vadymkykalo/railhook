package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.TunnelSession;
import com.webhook.platform.api.domain.enums.TunnelStatus;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.TunnelSessionRepository;
import com.webhook.platform.api.dto.TunnelSessionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TunnelServiceTest {

    private final UUID tenantOrgId = UUID.randomUUID();

    @Mock
    private TunnelSessionRepository tunnelSessionRepository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private TunnelService tunnelService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tunnelService, "ingressBaseUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(tunnelService, "heartbeatTimeoutSeconds", 120);
    }


    /**
     * Every service under test now reads its organization from the ambient tenant scope instead
     * of taking it as a parameter. A unit test has no request to establish one, so it
     * enters the scope itself; without this the first call fails with TenantNotResolvedException.
     */
    @BeforeEach
    void enterTenantScope() {
        TenantContext.set(tenantOrgId);
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    @Test
    void shouldCreateSession() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        // Mock project belongs to the same org
        Project project = Project.builder().id(projectId).organizationId(tenantOrgId).build();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        when(tunnelSessionRepository.save(any(TunnelSession.class)))
                .thenAnswer(inv -> {
                    TunnelSession s = inv.getArgument(0);
                    s.setId(UUID.randomUUID());
                    s.setCreatedAt(Instant.now());
                    return s;
                });

        TunnelSession session = tunnelService.createSession(userId, projectId, 3000, "cli/1.0");

        assertNotNull(session);
        assertEquals(userId, session.getUserId());
        assertEquals(tenantOrgId, session.getOrganizationId());
        assertEquals(projectId, session.getProjectId());
        assertEquals(3000, session.getLocalPort());
        assertEquals(TunnelStatus.ACTIVE, session.getStatus());
        assertNotNull(session.getTunnelToken());
        assertNotNull(session.getPublicSlug());
        assertTrue(session.getPublicSlug().startsWith("tun-"));
        assertEquals("cli/1.0", session.getClientInfo());

        verify(tunnelSessionRepository).save(any(TunnelSession.class));
    }

    @Test
    void shouldCloseSessionByToken() {
        TunnelSession session = TunnelSession.builder()
                .id(UUID.randomUUID())
                .tunnelToken("test-token")
                .publicSlug("tun-abc123")
                .status(TunnelStatus.ACTIVE)
                .build();

        when(tunnelSessionRepository.findByTunnelToken("test-token")).thenReturn(Optional.of(session));
        when(tunnelSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        tunnelService.closeSession("test-token");

        ArgumentCaptor<TunnelSession> captor = ArgumentCaptor.forClass(TunnelSession.class);
        verify(tunnelSessionRepository).save(captor.capture());
        assertEquals(TunnelStatus.CLOSED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getClosedAt());
    }

    @Test
    void shouldCloseSessionById() {
        UUID sessionId = UUID.randomUUID();
        TunnelSession session = TunnelSession.builder()
                .id(sessionId)
                .tunnelToken("test-token")
                .publicSlug("tun-xyz789")
                .status(TunnelStatus.ACTIVE)
                .build();

        when(tunnelSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(tunnelSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        tunnelService.closeSession(sessionId);

        verify(tunnelSessionRepository).save(argThat(s -> s.getStatus() == TunnelStatus.CLOSED));
    }

    @Test
    void shouldUpdateHeartbeat() {
        TunnelSession session = TunnelSession.builder()
                .id(UUID.randomUUID())
                .tunnelToken("hb-token")
                .status(TunnelStatus.ACTIVE)
                .build();

        when(tunnelSessionRepository.findByTunnelToken("hb-token")).thenReturn(Optional.of(session));
        when(tunnelSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        tunnelService.heartbeat("hb-token");

        verify(tunnelSessionRepository).save(argThat(s -> s.getLastHeartbeat() != null));
    }

    @Test
    void shouldGetActiveBySlug() {
        TunnelSession session = TunnelSession.builder()
                .id(UUID.randomUUID())
                .publicSlug("tun-active")
                .status(TunnelStatus.ACTIVE)
                .build();

        when(tunnelSessionRepository.findByPublicSlug("tun-active")).thenReturn(Optional.of(session));

        TunnelSession result = tunnelService.getActiveBySlug("tun-active");
        assertNotNull(result);
        assertEquals(TunnelStatus.ACTIVE, result.getStatus());
    }

    @Test
    void shouldThrowWhenSlugNotFound() {
        when(tunnelSessionRepository.findByPublicSlug("missing")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> tunnelService.getActiveBySlug("missing"));
    }

    @Test
    void shouldThrowWhenSlugNotActive() {
        TunnelSession session = TunnelSession.builder()
                .id(UUID.randomUUID())
                .publicSlug("tun-closed")
                .status(TunnelStatus.CLOSED)
                .build();

        when(tunnelSessionRepository.findByPublicSlug("tun-closed")).thenReturn(Optional.of(session));

        assertThrows(ResponseStatusException.class, () -> tunnelService.getActiveBySlug("tun-closed"));
    }

    @Test
    void shouldListActiveTunnels() {
        TunnelSession session = TunnelSession.builder()
                .id(UUID.randomUUID())
                .organizationId(tenantOrgId)
                .userId(UUID.randomUUID())
                .publicSlug("tun-list1")
                .localPort(3000)
                .status(TunnelStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();

        when(tunnelSessionRepository.findByOrganizationIdAndStatus(tenantOrgId, TunnelStatus.ACTIVE))
                .thenReturn(List.of(session));

        List<TunnelSessionResponse> results = tunnelService.listActive();
        assertEquals(1, results.size());
        assertEquals("tun-list1", results.get(0).getPublicSlug());
        assertTrue(results.get(0).getPublicUrl().contains("tun-list1"));
    }

    @Test
    void shouldBuildPublicUrl() {
        String url = tunnelService.buildPublicUrl("tun-slug123");
        assertEquals("http://localhost:8080/tunnel/tun-slug123", url);
    }

    @Test
    void shouldConvertToResponse() {
        TunnelSession session = TunnelSession.builder()
                .id(UUID.randomUUID())
                .organizationId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .publicSlug("tun-resp")
                .localPort(4000)
                .status(TunnelStatus.ACTIVE)
                .createdAt(Instant.now())
                .lastHeartbeat(Instant.now())
                .clientInfo("test-client")
                .build();

        TunnelSessionResponse response = tunnelService.toResponse(session);

        assertEquals(session.getId(), response.getId());
        assertEquals(session.getOrganizationId(), response.getOrganizationId());
        assertEquals(session.getUserId(), response.getUserId());
        assertEquals(session.getProjectId(), response.getProjectId());
        assertEquals("tun-resp", response.getPublicSlug());
        assertEquals("http://localhost:8080/tunnel/tun-resp", response.getPublicUrl());
        assertEquals(4000, response.getLocalPort());
        assertEquals(TunnelStatus.ACTIVE, response.getStatus());
        assertEquals("test-client", response.getClientInfo());
    }
}
