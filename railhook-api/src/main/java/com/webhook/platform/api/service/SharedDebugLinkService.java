package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.SharedDebugLink;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.SharedDebugLinkRepository;
import com.webhook.platform.api.dto.SharedDebugLinkPublicResponse;
import com.webhook.platform.api.dto.SharedDebugLinkRequest;
import com.webhook.platform.api.dto.SharedDebugLinkResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.util.CryptoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SharedDebugLinkService {

    private final SharedDebugLinkRepository linkRepository;
    private final EventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final PiiMaskingService piiMaskingService;

    @Value("${app.base-url:http://localhost:5173}")
    private String baseUrl;

    @Transactional
    public SharedDebugLinkResponse createLink(UUID projectId, UUID eventId, SharedDebugLinkRequest request,
                                               UUID userId) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(projectId)
                .filter(p -> p.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new NotFoundException("Project not found"));

        Event event = eventRepository.findById(eventId)
                .filter(e -> e.getProjectId().equals(projectId))
                .orElseThrow(() -> new NotFoundException("Event not found"));

        int expiryHours = request.getExpiryHours() != null ? request.getExpiryHours() : 24;
        String token = CryptoUtils.generateSecureToken(32);

        SharedDebugLink link = SharedDebugLink.builder()
                .projectId(projectId)
                .eventId(eventId)
                .token(token)
                .createdBy(userId)
                .expiresAt(Instant.now().plus(expiryHours, ChronoUnit.HOURS))
                .build();

        link = linkRepository.save(link);
        log.info("Created shared debug link for event {} in project {}, expires in {}h", eventId, projectId, expiryHours);
        return toResponse(link);
    }

    @Transactional(readOnly = true)
    public List<SharedDebugLinkResponse> listLinks(UUID projectId) {
        UUID organizationId = TenantContext.require();
        projectRepository.findById(projectId)
                .filter(p -> p.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new NotFoundException("Project not found"));

        return linkRepository.findByProjectId(projectId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SharedDebugLinkResponse> listLinksForEvent(UUID projectId, UUID eventId) {
        UUID organizationId = TenantContext.require();
        projectRepository.findById(projectId)
                .filter(p -> p.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new NotFoundException("Project not found"));

        // Filtered by projectId, not just by eventId. The response carries the raw share
        // token and its URL, and ScopeEnforcementInterceptor.enforceProjectScope confines an
        // API key to the {projectId} in the path while saying nothing about {eventId} — so
        // without this a key scoped to one project could name any event of a sibling project,
        // take the token, and read that event through the unauthenticated
        // /public/debug/{token} endpoint. createLink already checks the event belongs to the
        // project; this is the same check on the way back out.
        return linkRepository.findByEventId(eventId).stream()
                .filter(link -> projectId.equals(link.getProjectId()))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteLink(UUID projectId, UUID linkId) {
        UUID organizationId = TenantContext.require();
        projectRepository.findById(projectId)
                .filter(p -> p.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new NotFoundException("Project not found"));

        SharedDebugLink link = linkRepository.findById(linkId)
                .filter(l -> l.getProjectId().equals(projectId))
                .orElseThrow(() -> new NotFoundException("Debug link not found"));

        linkRepository.delete(link);
        log.info("Deleted shared debug link {} for project {}", linkId, projectId);
    }

    /**
     * Public endpoint — no auth required, token-based access.
     * Returns sanitized payload (PII masked).
     */
    @SystemTenant("the share token in the URL is the only identity a public debug link carries; it resolves the link and its Event unscoped")
    @Transactional
    public SharedDebugLinkPublicResponse viewPublicLink(String token) {
        SharedDebugLink link = linkRepository.findByToken(token)
                .orElseThrow(() -> new NotFoundException("Debug link not found or expired"));

        if (link.getExpiresAt().isBefore(Instant.now())) {
            throw new NotFoundException("Debug link has expired");
        }

        linkRepository.incrementViewCount(token);

        Event event = eventRepository.findById(link.getEventId())
                .orElseThrow(() -> new NotFoundException("Event not found"));

        Project project = projectRepository.findById(link.getProjectId())
                .orElseThrow(() -> new NotFoundException("Project not found"));

        String sanitizedPayload = piiMaskingService.sanitizePayload(link.getProjectId(), event.getDecompressedPayload());

        return SharedDebugLinkPublicResponse.builder()
                .eventType(event.getEventType())
                .sanitizedPayload(sanitizedPayload)
                .eventCreatedAt(event.getCreatedAt())
                .linkExpiresAt(link.getExpiresAt())
                .projectName(project.getName())
                .build();
    }

    private SharedDebugLinkResponse toResponse(SharedDebugLink link) {
        return SharedDebugLinkResponse.builder()
                .id(link.getId())
                .projectId(link.getProjectId())
                .eventId(link.getEventId())
                .token(link.getToken())
                .shareUrl(baseUrl + "/shared/debug/" + link.getToken())
                .expiresAt(link.getExpiresAt())
                .createdAt(link.getCreatedAt())
                .viewCount(link.getViewCount())
                .build();
    }
}
