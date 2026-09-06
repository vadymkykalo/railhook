package com.webhook.platform.api.service.workflow;

import com.webhook.platform.api.domain.repository.WorkflowExecutionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WorkflowExecutionRecoveryJobTest {

    @Test
    void recoverStuckExecutions_computesCutoffFromConfiguredThreshold() {
        WorkflowExecutionRepository repository = mock(WorkflowExecutionRepository.class);
        when(repository.failStuckExecutions(any(), anyString(), any())).thenReturn(0);

        WorkflowExecutionRecoveryJob job = new WorkflowExecutionRecoveryJob(repository, 15);

        Instant before = Instant.now().minus(15, ChronoUnit.MINUTES);
        job.recoverStuckExecutions();
        Instant after = Instant.now().minus(15, ChronoUnit.MINUTES);

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).failStuckExecutions(cutoffCaptor.capture(), anyString(), any());

        Instant cutoff = cutoffCaptor.getValue();
        assertThat(cutoff).isBetween(before.minusSeconds(2), after.plusSeconds(2));
    }

    @Test
    void recoverStuckExecutions_repositoryException_isSwallowedNotPropagated() {
        WorkflowExecutionRepository repository = mock(WorkflowExecutionRepository.class);
        when(repository.failStuckExecutions(any(), anyString(), any()))
                .thenThrow(new RuntimeException("db unreachable"));

        WorkflowExecutionRecoveryJob job = new WorkflowExecutionRecoveryJob(repository, 15);

        assertDoesNotThrow(job::recoverStuckExecutions,
                "the recovery job must not let a repository failure escape — it runs on a @Scheduled thread");
    }

    @Test
    void recoverStuckExecutions_zeroRecovered_doesNotThrow() {
        WorkflowExecutionRepository repository = mock(WorkflowExecutionRepository.class);
        when(repository.failStuckExecutions(any(), anyString(), any())).thenReturn(0);

        WorkflowExecutionRecoveryJob job = new WorkflowExecutionRecoveryJob(repository, 15);

        assertDoesNotThrow(job::recoverStuckExecutions);
        verify(repository, times(1)).failStuckExecutions(any(), anyString(), any());
    }

    @Test
    void recoverStuckExecutions_errorMessageMentionsThresholdMinutes() {
        WorkflowExecutionRepository repository = mock(WorkflowExecutionRepository.class);
        when(repository.failStuckExecutions(any(), anyString(), any())).thenReturn(2);

        WorkflowExecutionRecoveryJob job = new WorkflowExecutionRecoveryJob(repository, 42);
        job.recoverStuckExecutions();

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).failStuckExecutions(any(), msgCaptor.capture(), any());
        assertThat(msgCaptor.getValue()).contains("42");
    }
}
