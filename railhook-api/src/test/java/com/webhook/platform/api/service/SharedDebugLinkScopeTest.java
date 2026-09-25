package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.SharedDebugLink;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.SharedDebugLinkRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

// listLinksForEvent loaded links by eventId alone, handing a project-scoped key another project's token.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SharedDebugLinkScopeTest {

    @Mock private SharedDebugLinkRepository linkRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private EventRepository eventRepository;
    @Mock private PiiMaskingService piiMaskingService;

    private SharedDebugLinkService service;
    private UUID organizationId;
    private UUID projectA;
    private UUID projectB;

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        projectA = UUID.randomUUID();
        projectB = UUID.randomUUID();
        TenantContext.set(organizationId);

        service = new SharedDebugLinkService(
                linkRepository, eventRepository, projectRepository, piiMaskingService);

        Project a = new Project();
        a.setId(projectA);
        a.setOrganizationId(organizationId);
        when(projectRepository.findById(projectA)).thenReturn(Optional.of(a));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aLinkBelongingToAnotherProjectIsNotReturned() {
        UUID foreignEventId = UUID.randomUUID();

        SharedDebugLink foreign = SharedDebugLink.builder()
                .id(UUID.randomUUID())
                .projectId(projectB)
                .eventId(foreignEventId)
                .token("secret-share-token")
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();
        when(linkRepository.findByEventId(foreignEventId)).thenReturn(List.of(foreign));

        assertTrue(service.listLinksForEvent(projectA, foreignEventId).isEmpty(),
                "a caller confined to one project must not receive another project's share token");
    }
}
