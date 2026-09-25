package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.Workflow;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.WorkflowExecutionRepository;
import com.webhook.platform.api.domain.repository.WorkflowRepository;
import com.webhook.platform.api.domain.repository.WorkflowStepExecutionRepository;
import com.webhook.platform.api.dto.WorkflowRequest;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.service.workflow.WorkflowEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// A createEvent node could name another organization's project and deliver to its endpoints.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowServiceTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowExecutionRepository executionRepository;
    @Mock private WorkflowStepExecutionRepository stepExecutionRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private EndpointRepository endpointRepository;
    @Mock private WorkflowEngine workflowEngine;

    private WorkflowService service;
    private final UUID ownProject = UUID.randomUUID();
    private final UUID foreignProject = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WorkflowService(workflowRepository, executionRepository, stepExecutionRepository,
                projectRepository, endpointRepository, new ObjectMapper(), workflowEngine);
        when(projectRepository.findById(ownProject)).thenReturn(Optional.of(
                Project.builder().id(ownProject).organizationId(UUID.randomUUID()).name("own").build()));
        when(projectRepository.findById(foreignProject)).thenReturn(Optional.empty());
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> {
            Workflow w = inv.getArgument(0);
            w.setId(UUID.randomUUID());
            return w;
        });
    }

    private WorkflowRequest withCreateEventInto(UUID target) {
        return WorkflowRequest.builder()
                .name("wf")
                .definition(Map.of(
                        "nodes", List.of(
                                Map.of("id", "start", "type", "webhookTrigger", "data", Map.of()),
                                Map.of("id", "emit", "type", "createEvent", "data",
                                        Map.of("projectId", target.toString(), "eventType", "x.y"))),
                        "edges", List.of(Map.of("source", "start", "target", "emit"))))
                .build();
    }

    @Test
    void create_refusesACreateEventNodeIntoAProjectTheCallerCannotSee() {
        assertThatThrownBy(() -> service.create(ownProject, withCreateEventInto(foreignProject)))
                .isInstanceOf(NotFoundException.class);
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void create_acceptsACreateEventNodeIntoTheCallersOwnProject() {
        service.create(ownProject, withCreateEventInto(ownProject));
        verify(workflowRepository).save(any(Workflow.class));
    }

    @Test
    void update_refusesACreateEventNodeIntoAProjectTheCallerCannotSee() {
        UUID workflowId = UUID.randomUUID();
        when(workflowRepository.findByIdAndProjectId(workflowId, ownProject)).thenReturn(Optional.of(
                Workflow.builder().id(workflowId).projectId(ownProject).name("wf").definition("{}").version(1).build()));

        assertThatThrownBy(() -> service.update(ownProject, workflowId, withCreateEventInto(foreignProject)))
                .isInstanceOf(NotFoundException.class);
        verify(workflowRepository, never()).save(any());
    }
}
