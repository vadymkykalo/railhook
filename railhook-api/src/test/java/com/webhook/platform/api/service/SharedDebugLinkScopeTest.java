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

/**
 * A share token must not cross a project boundary just because the caller asked nicely.
 *
 * <p>{@code listLinksForEvent} validated the {@code projectId} in the path and then loaded
 * links by {@code eventId} alone, never checking that the event belonged to that project —
 * unlike {@code createLink}, which does check. The response carries the raw token and the
 * share URL.</p>
 *
 * <p>That defeats the one control built for a leaked API key.
 * {@code ScopeEnforcementInterceptor.enforceProjectScope} confines a key to the
 * {@code {projectId}} in the URI template and says nothing about {@code {eventId}}, so a key
 * scoped to project A could name any event of project B, collect the token, and read that
 * event's payload through the unauthenticated {@code /public/debug/{token}} endpoint.</p>
 *
 * <p>The {@code @TenantId} on the entity keeps this inside one organization, so it is not a
 * cross-tenant leak — it is the project confinement failing, which is precisely the control
 * that is supposed to hold when a key has already been compromised.</p>
 */
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

        // Asking project A for the links of an event that lives in project B.
        assertTrue(service.listLinksForEvent(projectA, foreignEventId).isEmpty(),
                "a caller confined to one project must not receive another project's share token");
    }
}
