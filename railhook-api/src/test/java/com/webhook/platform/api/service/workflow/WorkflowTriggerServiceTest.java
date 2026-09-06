package com.webhook.platform.api.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Workflow;
import com.webhook.platform.api.domain.entity.WorkflowExecution;
import com.webhook.platform.api.domain.repository.WorkflowExecutionRepository;
import com.webhook.platform.api.domain.repository.WorkflowRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * Covers WorkflowTriggerService's recursion depth guard — the mechanism that
 * stops a workflow's own createEvent/delivery side effects from re-triggering
 * more workflows indefinitely — plus idempotency and per-workflow failure
 * isolation.
 */
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

    // ─── Depth ThreadLocal plumbing ──────────────────────────────────────

    @Test
    void currentDepth_defaultsToZero() {
        assertThat(WorkflowTriggerService.getCurrentDepth()).isZero();
    }

    @Test
    void setAndClearCurrentDepth_roundTrips() {
        WorkflowTriggerService.setCurrentDepth(4);
        assertThat(WorkflowTriggerService.getCurrentDepth()).isEqualTo(4);
        WorkflowTriggerService.clearCurrentDepth();
        assertThat(WorkflowTriggerService.getCurrentDepth()).isZero();
    }

    // ─── Recursion depth guard ───────────────────────────────────────────

    @Test
    void depthExceedsMax_skipsEntirely_neverTouchesWorkflowRepository() {
        WorkflowTriggerService service = newService(3);

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 4);

        verifyNoInteractions(workflowRepository);
        verifyNoInteractions(workflowEngine);
    }

    @Test
    void depthEqualToMax_stillProcesses_guardIsStrictlyGreaterThan() {
        WorkflowTriggerService service = newService(3);
        when(workflowRepository.findEnabledWebhookWorkflows(projectId)).thenReturn(List.of());

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 3);

        // depth == max is allowed through (guard is `depth > maxRecursionDepth`);
        // it still reaches the repository call, just finds no workflows.
        verify(workflowRepository).findEnabledWebhookWorkflows(projectId);
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
        // This is the actual guard mechanism end to end: WorkflowEngine reads
        // WorkflowTriggerService.getCurrentDepth() (via executeWithTimeout) while
        // setCurrentDepth(depth) is active for the duration of engine.execute(),
        // so anything the engine triggers downstream (e.g. CreateEventNodeExecutor
        // re-entering the ingest → trigger pipeline) sees the current hop count.
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
        // Cleared again afterward so it doesn't leak onto whatever runs next on this thread.
        assertThat(WorkflowTriggerService.getCurrentDepth()).isZero();
    }

    // ─── Idempotency ─────────────────────────────────────────────────────

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

    // ─── Trigger pattern matching ────────────────────────────────────────

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
    void noTriggerConfigPattern_matchesAllEvents() {
        WorkflowTriggerService service = newService(3);
        Workflow workflow = enabledWorkflow(null); // triggerConfig = "{}"
        when(workflowRepository.findEnabledWebhookWorkflows(projectId)).thenReturn(List.of(workflow));
        when(executionRepository.existsByWorkflowIdAndTriggerEventId(workflow.getId(), eventId)).thenReturn(false);
        when(executionRepository.save(any(WorkflowExecution.class))).thenAnswer(inv -> {
            WorkflowExecution e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        service.triggerWorkflowsSync(projectId, eventId, "anything.at.all", "{}", 0);

        verify(workflowEngine).execute(any(), any(), any());
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

    // ─── Malformed payload / no workflows ────────────────────────────────

    @Test
    void malformedEventPayload_doesNotThrow_skipsAllWorkflows() {
        WorkflowTriggerService service = newService(3);
        when(workflowRepository.findEnabledWebhookWorkflows(projectId))
                .thenReturn(List.of(enabledWorkflow(null)));

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{not valid json", 0);

        verifyNoInteractions(workflowEngine);
    }

    @Test
    void noEnabledWorkflows_returnsWithoutTouchingExecutionRepository() {
        WorkflowTriggerService service = newService(3);
        when(workflowRepository.findEnabledWebhookWorkflows(projectId)).thenReturn(List.of());

        service.triggerWorkflowsSync(projectId, eventId, "order.created", "{}", 0);

        verifyNoInteractions(executionRepository);
        verifyNoInteractions(workflowEngine);
    }

    // ─── Per-workflow failure isolation ──────────────────────────────────

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

    // ─── Tenant scope ────────────────────────────────────────────────────

    @Test
    void triggering_entersTheWorkflowsOrganizationScope_notTheCallersSystemScope() {
        // The only caller is the outbox poller, which runs as the system tenant. Under root,
        // Hibernate stamps nothing: whatever scope is live when executionRecord is saved is what
        // decides the row's organization_id, and NOT NULL makes "no scope" a rollback, not a
        // warning. Assert on the ambient scope rather than on the entity, because
        // the entity deliberately does not carry the value.
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
        // The engine and the node executors it drives write deliveries and outbox rows of their
        // own, so the scope has to still be there when they run — not only for the insert above.
        assertThat(scopeAtExecute.get()).isEqualTo(organizationId);
        // And it is given back: the poller goes on to mark the outbox row done as the system.
        assertThat(TenantContext.current()).isNull();
    }
}
