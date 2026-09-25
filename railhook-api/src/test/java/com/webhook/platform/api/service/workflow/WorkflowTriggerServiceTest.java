package com.webhook.platform.api.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Workflow;
import com.webhook.platform.api.domain.entity.WorkflowExecution;
import com.webhook.platform.api.domain.repository.WorkflowExecutionRepository;
import com.webhook.platform.api.domain.repository.WorkflowRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowTriggerServiceTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowExecutionRepository executionRepository;
    @Mock private WorkflowEngine workflowEngine;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    private WorkflowTriggerService newService(int maxRecursionDepth) {
        return new WorkflowTriggerService(workflowRepository, executionRepository, workflowEngine,
                objectMapper, maxRecursionDepth);
    }

    private Workflow enabledWorkflow(String pattern) {
        return Workflow.builder()
                .id(UUID.randomUUID())
                .organizationId(organizationId)
                .projectId(projectId)
                .name("wf")
                .enabled(true)
                .definition("{\"nodes\":[],\"edges\":[]}")
                .triggerConfig(pattern != null ? "{\"eventTypePattern\":\"" + pattern + "\"}" : "{}")
                .build();
    }

    @AfterEach
    void clearThreadLocal() {
        WorkflowTriggerService.clearCurrentDepth();
    }

    @Test
    void depthExceedsMax_skipsEntirely_neverTouchesWorkflowRepository() {
        WorkflowTriggerService service = newService(3);

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 4);

        verifyNoInteractions(workflowRepository);
        verifyNoInteractions(workflowEngine);
    }

    @Test
    void depthOneOverMax_isBlocked_depthAtMaxIsNot() {
        WorkflowTriggerService service = newService(2);
        when(workflowRepository.findEnabledWebhookWorkflows(projectId)).thenReturn(List.of());

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 3);
        verifyNoInteractions(workflowRepository);

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 2);
        verify(workflowRepository).findEnabledWebhookWorkflows(projectId);
    }

    @Test
    void depthPropagatesToEngineExecute_soNestedWorkflowsInheritIncrementedDepth() {
        WorkflowTriggerService service = newService(5);
        Workflow workflow = enabledWorkflow(null);
        when(workflowRepository.findEnabledWebhookWorkflows(projectId)).thenReturn(List.of(workflow));
        when(executionRepository.existsByWorkflowIdAndTriggerEventId(workflow.getId(), eventId)).thenReturn(false);
        when(executionRepository.save(any(WorkflowExecution.class))).thenAnswer(inv -> {
            WorkflowExecution e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        AtomicInteger observedDuringExecute = new AtomicInteger(-1);
        doAnswer(inv -> {
            observedDuringExecute.set(WorkflowTriggerService.getCurrentDepth());
            return null;
        }).when(workflowEngine).execute(any(), any(), any());

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 2);

        assertThat(observedDuringExecute.get()).isEqualTo(2);
        assertThat(WorkflowTriggerService.getCurrentDepth()).isZero();
    }

    @Test
    void duplicateEvent_skipsCreatingExecution_engineNeverInvoked() {
        WorkflowTriggerService service = newService(3);
        Workflow workflow = enabledWorkflow(null);
        when(workflowRepository.findEnabledWebhookWorkflows(projectId)).thenReturn(List.of(workflow));
        when(executionRepository.existsByWorkflowIdAndTriggerEventId(workflow.getId(), eventId)).thenReturn(true);

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 0);

        verify(executionRepository, never()).save(any());
        verifyNoInteractions(workflowEngine);
    }

    @Test
    void concurrentDuplicate_dataIntegrityViolation_isSwallowedNotPropagated() {
        WorkflowTriggerService service = newService(3);
        Workflow workflow = enabledWorkflow(null);
        when(workflowRepository.findEnabledWebhookWorkflows(projectId)).thenReturn(List.of(workflow));
        when(executionRepository.existsByWorkflowIdAndTriggerEventId(workflow.getId(), eventId)).thenReturn(false);
        when(executionRepository.save(any(WorkflowExecution.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violated"));

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 0);

        verifyNoInteractions(workflowEngine);
    }

    @Test
    void nonMatchingEventTypePattern_skipsWorkflow() {
        WorkflowTriggerService service = newService(3);
        Workflow workflow = enabledWorkflow("payment.*");
        when(workflowRepository.findEnabledWebhookWorkflows(projectId)).thenReturn(List.of(workflow));

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 0);

        verify(executionRepository, never())
                .existsByWorkflowIdAndTriggerEventId(any(), any());
        verifyNoInteractions(workflowEngine);
    }

    @Test
    void matchingWildcardPattern_triggersWorkflow() {
        WorkflowTriggerService service = newService(3);
        Workflow workflow = enabledWorkflow("order.*");
        when(workflowRepository.findEnabledWebhookWorkflows(projectId)).thenReturn(List.of(workflow));
        when(executionRepository.existsByWorkflowIdAndTriggerEventId(workflow.getId(), eventId)).thenReturn(false);
        when(executionRepository.save(any(WorkflowExecution.class))).thenAnswer(inv -> {
            WorkflowExecution e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 0);

        verify(workflowEngine).execute(any(), eq(workflow.getDefinition()), any());
    }

    @Test
    void malformedTriggerConfig_treatsAsNonMatching() {
        WorkflowTriggerService service = newService(3);
        Workflow workflow = Workflow.builder().id(UUID.randomUUID()).organizationId(organizationId)
                .projectId(projectId).name("bad")
                .enabled(true).definition("{}").triggerConfig("{not valid json").build();
        when(workflowRepository.findEnabledWebhookWorkflows(projectId)).thenReturn(List.of(workflow));

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 0);

        verifyNoInteractions(workflowEngine);
    }

    @Test
    void malformedEventPayload_doesNotThrow_skipsAllWorkflows() {
        WorkflowTriggerService service = newService(3);
        when(workflowRepository.findEnabledWebhookWorkflows(projectId))
                .thenReturn(List.of(enabledWorkflow(null)));

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{not valid json", 0);

        verifyNoInteractions(workflowEngine);
    }

    @Test
    void oneWorkflowThrows_othersStillTriggered() {
        WorkflowTriggerService service = newService(3);
        Workflow failing = enabledWorkflow(null);
        Workflow healthy = enabledWorkflow(null);
        when(workflowRepository.findEnabledWebhookWorkflows(projectId)).thenReturn(List.of(failing, healthy));
        when(executionRepository.existsByWorkflowIdAndTriggerEventId(any(), eq(eventId))).thenReturn(false);
        when(executionRepository.save(any(WorkflowExecution.class))).thenAnswer(inv -> {
            WorkflowExecution e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        doThrow(new RuntimeException("engine blew up"))
                .doNothing()
                .when(workflowEngine).execute(any(), any(), any());

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 0);

        verify(workflowEngine, times(2)).execute(any(), any(), any());
    }

    @Test
    void executionRecord_carriesRequestedDepth() {
        WorkflowTriggerService service = newService(5);
        Workflow workflow = enabledWorkflow(null);
        when(workflowRepository.findEnabledWebhookWorkflows(projectId)).thenReturn(List.of(workflow));
        when(executionRepository.existsByWorkflowIdAndTriggerEventId(workflow.getId(), eventId)).thenReturn(false);
        when(executionRepository.save(any(WorkflowExecution.class))).thenAnswer(inv -> {
            WorkflowExecution e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 3);

        ArgumentCaptor<WorkflowExecution> captor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(executionRepository).save(captor.capture());
        assertThat(captor.getValue().getDepth()).isEqualTo(3);
        assertThat(captor.getValue().getWorkflowId()).isEqualTo(workflow.getId());
        assertThat(captor.getValue().getTriggerEventId()).isEqualTo(eventId);
    }

    @Test
    void triggering_entersTheWorkflowsOrganizationScope_notTheCallersSystemScope() {
        // The poller runs as the system tenant; the scope live at save decides the row's organization_id.
        WorkflowTriggerService service = newService(3);
        Workflow workflow = enabledWorkflow(null);
        when(workflowRepository.findEnabledWebhookWorkflows(projectId)).thenReturn(List.of(workflow));
        when(executionRepository.existsByWorkflowIdAndTriggerEventId(any(), eq(eventId))).thenReturn(false);

        AtomicReference<UUID> scopeAtSave = new AtomicReference<>();
        AtomicReference<UUID> scopeAtExecute = new AtomicReference<>();
        when(executionRepository.save(any(WorkflowExecution.class))).thenAnswer(inv -> {
            scopeAtSave.set(TenantContext.current());
            WorkflowExecution e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        doAnswer(inv -> {
            scopeAtExecute.set(TenantContext.current());
            return null;
        }).when(workflowEngine).execute(any(), any(), any());

        TenantContext.runAsSystem(() ->
                service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 0));

        assertThat(scopeAtSave.get()).isEqualTo(organizationId);
        assertThat(scopeAtExecute.get()).isEqualTo(organizationId);
        assertThat(TenantContext.current()).isNull();
    }
}
