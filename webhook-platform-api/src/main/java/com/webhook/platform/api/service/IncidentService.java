package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.domain.entity.IncidentTimeline;
import com.webhook.platform.api.domain.enums.AlertSeverity;
import com.webhook.platform.api.domain.enums.IncidentStatus;
import com.webhook.platform.api.domain.enums.IncidentTimelineType;
import com.webhook.platform.api.domain.repository.IncidentRepository;
import com.webhook.platform.api.domain.repository.IncidentTimelineRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.IncidentCountsResponse;
import com.webhook.platform.api.dto.IncidentRequest;
import com.webhook.platform.api.dto.IncidentResponse;
import com.webhook.platform.api.dto.TimelineEntryRequest;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentService {

    private final IncidentRepository incidentRepository;
    private final IncidentTimelineRepository timelineRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public Page<IncidentResponse> listIncidents(UUID projectId, boolean openOnly, int page, int size) {
        validateProjectAccess(projectId);
        Page<Incident> incidents;
        if (openOnly) {
            incidents = incidentRepository.findByProjectIdAndStatusNotOrderByCreatedAtDesc(
                    projectId, IncidentStatus.RESOLVED, PageRequest.of(page, Math.min(size, 100)));
        } else {
            incidents = incidentRepository.findByProjectIdOrderByCreatedAtDesc(
                    projectId, PageRequest.of(page, Math.min(size, 100)));
        }
        return incidents.map(IncidentResponse::of);
    }

    @Transactional(readOnly = true)
    public IncidentResponse getIncident(UUID projectId, UUID incidentId) {
        validateProjectAccess(projectId);
        Incident incident = incidentRepository.findByIdAndProjectId(incidentId, projectId)
                .orElseThrow(() -> new NotFoundException("Incident not found"));
        IncidentResponse response = IncidentResponse.of(incident);
        List<IncidentTimeline> timeline = timelineRepository.findByIncidentIdOrderByCreatedAtAsc(incidentId);
        response.setTimeline(timeline.stream().map(this::toTimelineEntry).toList());
        return response;
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "Incident")
    @Transactional
    public IncidentResponse createIncident(UUID projectId, IncidentRequest request) {
        validateProjectAccess(projectId);
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("Incident title is required");
        }

        Incident incident = Incident.builder()
                .projectId(projectId)
                .title(request.getTitle())
                .severity(request.getSeverity() != null ? request.getSeverity() : AlertSeverity.WARNING)
                .status(IncidentStatus.OPEN)
                .rcaNotes(request.getRcaNotes())
                .build();

        incident = incidentRepository.save(incident);

        // Add initial timeline entry
        IncidentTimeline entry = IncidentTimeline.builder()
                .incidentId(incident.getId())
                .entryType(IncidentTimelineType.STATUS_CHANGE)
                .title("Incident created")
                .detail(request.getTitle())
                .build();
        timelineRepository.save(entry);

        log.info("Created incident '{}' for project {}", incident.getTitle(), projectId);
        return getIncident(projectId, incident.getId());
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "Incident")
    @Transactional
    public IncidentResponse updateIncident(UUID projectId, UUID incidentId, IncidentRequest request) {
        validateProjectAccess(projectId);
        Incident incident = incidentRepository.findByIdAndProjectId(incidentId, projectId)
                .orElseThrow(() -> new NotFoundException("Incident not found"));

        IncidentStatus oldStatus = incident.getStatus();

        if (request.getTitle() != null) incident.setTitle(request.getTitle());
        if (request.getSeverity() != null) incident.setSeverity(request.getSeverity());
        if (request.getRcaNotes() != null) incident.setRcaNotes(request.getRcaNotes());
        if (request.getStatus() != null) {
            incident.setStatus(request.getStatus());
            if (request.getStatus() == IncidentStatus.RESOLVED && incident.getResolvedAt() == null) {
                incident.setResolvedAt(Instant.now());
            }
        }

        incident = incidentRepository.save(incident);

        // Add status change timeline entry if status changed
        if (request.getStatus() != null && request.getStatus() != oldStatus) {
            IncidentTimeline entry = IncidentTimeline.builder()
                    .incidentId(incidentId)
                    .entryType(IncidentTimelineType.STATUS_CHANGE)
                    .title("Status changed to " + request.getStatus())
                    .build();
            timelineRepository.save(entry);
        }

        return getIncident(projectId, incidentId);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "IncidentTimeline")
    @Transactional
    public IncidentResponse addTimelineEntry(UUID projectId, UUID incidentId, TimelineEntryRequest request) {
        validateProjectAccess(projectId);
        incidentRepository.findByIdAndProjectId(incidentId, projectId)
                .orElseThrow(() -> new NotFoundException("Incident not found"));

        IncidentTimeline entry = IncidentTimeline.builder()
                .incidentId(incidentId)
                .entryType(request.getEntryType())
                .title(request.getTitle())
                .detail(request.getDetail())
                .deliveryId(request.getDeliveryId())
                .endpointId(request.getEndpointId())
                .build();
        timelineRepository.save(entry);

        return getIncident(projectId, incidentId);
    }

    /**
     * All three counts in one call, because they are read together and shown together.
     *
     * <p>Three queries rather than one grouped scan: the page asks for this once on load and
     * again after a status change, the row counts are small, and a grouped query would have to
     * be reassembled into the same three numbers anyway.
     */
    @Transactional(readOnly = true)
    public IncidentCountsResponse countUnresolved(UUID projectId) {
        validateProjectAccess(projectId);
        return new IncidentCountsResponse(
                incidentRepository.countByProjectIdAndStatusNot(projectId, IncidentStatus.RESOLVED),
                incidentRepository.countByProjectIdAndStatus(projectId, IncidentStatus.INVESTIGATING),
                incidentRepository.countByProjectIdAndSeverityAndStatusNot(
                        projectId, AlertSeverity.CRITICAL, IncidentStatus.RESOLVED));
    }

    private IncidentResponse.TimelineEntry toTimelineEntry(IncidentTimeline tl) {
        return IncidentResponse.TimelineEntry.builder()
                .id(tl.getId())
                .entryType(tl.getEntryType().name())
                .title(tl.getTitle())
                .detail(tl.getDetail())
                .deliveryId(tl.getDeliveryId())
                .endpointId(tl.getEndpointId())
                .createdAt(tl.getCreatedAt())
                .build();
    }

    private void validateProjectAccess(UUID projectId) {
        UUID organizationId = TenantContext.require();
        projectRepository.findById(projectId)
                .filter(p -> p.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }
}
