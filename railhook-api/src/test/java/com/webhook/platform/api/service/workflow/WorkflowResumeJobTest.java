package com.webhook.platform.api.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Workflow;
import com.webhook.platform.api.domain.entity.WorkflowExecution;
import com.webhook.platform.api.domain.entity.WorkflowExecution.ExecutionStatus;
import com.webhook.platform.api.domain.repository.WorkflowExecutionRepository;
import com.webhook.platform.api.domain.repository.WorkflowRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The other half of not sleeping: something has to wake the execution up.
 *
 * <p>A suspended execution is a row nobody is holding. If this job loses one — because the
 * workflow was deleted, or the snapshot cannot be read — the row sits in WAITING and is polled
 * forever, which is a slower version of the leak the suspension was meant to fix. So every path
 * out of here is terminal in one direction or the other.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkflowResumeJob — nothing suspended is left behind")
class WorkflowResumeJobTest {

    @Mock private WorkflowExecutionRepository executionRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowEngine engine;

    private final ObjectMapper mapper = new ObjectMapper();

    private final UUID organizationId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private WorkflowResumeJob job() {
        return new WorkflowResumeJob(executionRepository, workflowRepository, engine, mapper, 50);
    }

    @Test
    @DisplayName("a due execution is resumed inside its own organization")
    void resumesInsideItsOwnTenant() {
        WorkflowExecution execution = suspended();
        when(executionRepository.findDueForResume(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(execution));
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(
                Workflow.builder().id(workflowId).definition("{\"nodes\":[],\"edges\":[]}").build()));

        AtomicReference<UUID> tenantAtResume = new AtomicReference<>();
        doAnswer(inv -> {
            tenantAtResume.set(TenantContext.current());
            return null;
        }).when(engine).resume(any(), any(), any(), any(), anyLong());

        job().resumeDueExecutions();

        /* The job runs @SystemTenant, with Hibernate's tenant filter off. The engine writes
           step rows, which are tenant-scoped — resuming without re-entering would write them
           owned by nobody. */
        assertThat(tenantAtResume.get()).isEqualTo(organizationId);
    }

    @Test
    @DisplayName("the accumulated working time is carried into the resumed run")
    void carriesWorkingTimeForward() {
        WorkflowExecution execution = suspended();
        execution.setWorkingMs(1234L);
        when(executionRepository.findDueForResume(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(execution));
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(
                Workflow.builder().id(workflowId).definition("{\"nodes\":[],\"edges\":[]}").build()));

        job().resumeDueExecutions();

        /* Dropping it would reset the execution budget on every suspension, so a workflow that
           alternated delays and work could run forever without ever tripping the timeout. */
        verify(engine).resume(eq(execution.getId()), any(), any(), any(), eq(1234L));
    }

    @Test
    @DisplayName("an execution whose workflow was deleted is failed, not polled forever")
    void deletedWorkflowIsFailedTerminally() {
        WorkflowExecution execution = suspended();
        when(executionRepository.findDueForResume(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(execution));
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.empty());

        job().resumeDueExecutions();

        verify(engine, never()).resume(any(), any(), any(), any(), anyLong());
        ArgumentCaptor<WorkflowExecution> saved = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(executionRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(saved.getValue().getResumeAt())
                .as("a failed execution must stop matching the due query")
                .isNull();
    }

    @Test
    @DisplayName("an unreadable snapshot fails that execution and leaves the batch alone")
    void unreadableStateDoesNotStopTheBatch() {
        WorkflowExecution broken = suspended();
        broken.setResumeState("{ this is not json");
        WorkflowExecution healthy = suspended();
        when(executionRepository.findDueForResume(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(broken, healthy));
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(
                Workflow.builder().id(workflowId).definition("{\"nodes\":[],\"edges\":[]}").build()));

        job().resumeDueExecutions();

        /* The two rows belong to different organizations as far as this job knows. One
           customer's unreadable snapshot must not hold up everyone else's delays. */
        verify(engine).resume(eq(healthy.getId()), any(), any(), any(), anyLong());
    }

    private WorkflowExecution suspended() {
        JsonNode state = mapper.createObjectNode().put("resumeFrom", "b");
        return WorkflowExecution.builder()
                .id(UUID.randomUUID())
                .organizationId(organizationId)
                .workflowId(workflowId)
                .status(ExecutionStatus.WAITING)
                .resumeAt(Instant.now().minusSeconds(5))
                .resumeState(state.toString())
                .triggerData("{}")
                .startedAt(Instant.now().minusSeconds(120))
                .build();
    }
}
