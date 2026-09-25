package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.TunnelSession;
import com.webhook.platform.api.domain.enums.TunnelStatus;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.TunnelSessionRepository;
import com.webhook.platform.api.service.billing.EntitlementService;
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
import java.util.HashSet;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private EntitlementService entitlementService;

    @Mock
    private RedisTunnelCoordinator redisTunnelCoordinator;

    @InjectMocks
    private TunnelService tunnelService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tunnelService, "ingressBaseUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(tunnelService, "heartbeatTimeoutSeconds", 120);
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
    void shouldCreateSession() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

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

    // Deleting a tunnel only marked its row CLOSED, and the socket kept forwarding unmetered.
    @Test
    void closingASessionByIdDisconnectsItsTunnelEverywhere() {
        UUID sessionId = UUID.randomUUID();
        TunnelSession session = TunnelSession.builder()
                .id(sessionId).tunnelToken("t").publicSlug("tun-closeme").status(TunnelStatus.ACTIVE).build();
        when(tunnelSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(tunnelSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        tunnelService.closeSession(sessionId);

        verify(redisTunnelCoordinator).disconnect("tun-closeme");
    }

    @Test
    void closingASessionByTokenDisconnectsItsTunnelEverywhere() {
        TunnelSession session = TunnelSession.builder()
                .id(UUID.randomUUID()).tunnelToken("tok").publicSlug("tun-bytoken").status(TunnelStatus.ACTIVE).build();
        when(tunnelSessionRepository.findByTunnelToken("tok")).thenReturn(Optional.of(session));
        when(tunnelSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        tunnelService.closeSession("tok");

        verify(redisTunnelCoordinator).disconnect("tun-bytoken");
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

    // A slug cut out of base64 came up short when enough '-' and '_' were stripped, and creation threw.
    @Test
    void everySlugIsTwelveAlphanumericCharactersAfterTheTunPrefix() {
        Random random = new Random(20260922L);
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            String slug = TunnelService.slug(random);
            assertThat(slug).matches("tun-[a-z0-9]{12}");
            seen.add(slug);
        }

        assertThat(seen).as("a slug is a name in a URL, so it may not repeat").hasSize(10_000);
    }

    @Test
    void aSlugFromASourceThatKeepsReturningTheFirstCharacterStillHasTwelve() {
        String slug = TunnelService.slug(new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        });

        assertThat(slug).matches("tun-[a-z0-9]{12}");
    }
}
