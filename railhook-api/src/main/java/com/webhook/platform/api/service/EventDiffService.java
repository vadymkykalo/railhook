package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.EventDiffResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventDiffService {

    private final EventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final PiiMaskingService piiMaskingService;
    private final JsonDiffCalculator jsonDiffCalculator;

    @Transactional(readOnly = true)
    public EventDiffResponse diff(UUID projectId, UUID leftEventId, UUID rightEventId,
                                   boolean sanitize) {
        UUID organizationId = TenantContext.require();
        projectRepository.findById(projectId)
                .filter(p -> p.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new NotFoundException("Project not found"));

        Event leftEvent = eventRepository.findById(leftEventId)
                .filter(e -> e.getProjectId().equals(projectId))
                .orElseThrow(() -> new NotFoundException("Left event not found"));

        Event rightEvent = eventRepository.findById(rightEventId)
                .filter(e -> e.getProjectId().equals(projectId))
                .orElseThrow(() -> new NotFoundException("Right event not found"));

        String leftPayload = leftEvent.getDecompressedPayload();
        String rightPayload = rightEvent.getDecompressedPayload();

        if (sanitize) {
            leftPayload = piiMaskingService.sanitizePayload(projectId, leftPayload);
            rightPayload = piiMaskingService.sanitizePayload(projectId, rightPayload);
        }

        return EventDiffResponse.builder()
                .leftEventId(leftEventId)
                .rightEventId(rightEventId)
                .eventType(leftEvent.getEventType())
                .leftCreatedAt(leftEvent.getCreatedAt())
                .rightCreatedAt(rightEvent.getCreatedAt())
                .leftPayload(leftPayload)
                .rightPayload(rightPayload)
                .diffs(jsonDiffCalculator.diff(leftPayload, rightPayload))
                .build();
    }
}
