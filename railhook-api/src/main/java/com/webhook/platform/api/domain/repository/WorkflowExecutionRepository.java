package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.WorkflowExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, UUID> {

    Optional<WorkflowExecution> findByIdAndWorkflowId(UUID id, UUID workflowId);

    Page<WorkflowExecution> findByWorkflowIdOrderByStartedAtDesc(UUID workflowId, Pageable pageable);

    long countByWorkflowIdAndStatus(UUID workflowId, WorkflowExecution.ExecutionStatus status);

    @Query("SELECT e.workflowId, e.status, COUNT(e) FROM WorkflowExecution e "
            + "WHERE e.workflowId IN :workflowIds GROUP BY e.workflowId, e.status")
    List<Object[]> countByWorkflowIdsGroupedByStatus(@Param("workflowIds") Collection<UUID> workflowIds);

    boolean existsByWorkflowIdAndTriggerEventId(UUID workflowId, UUID triggerEventId);

    @Query("SELECT e FROM WorkflowExecution e WHERE e.status = 'RUNNING' AND e.startedAt < :cutoff")
    List<WorkflowExecution> findStuckExecutions(@Param("cutoff") Instant cutoff);

    // findStuckExecutions matching RUNNING only is what keeps a suspended execution from being
    // failed as hung.
    @Query("SELECT e FROM WorkflowExecution e WHERE e.status = 'WAITING' AND e.resumeAt <= :now "
            + "ORDER BY e.resumeAt ASC")
    List<WorkflowExecution> findDueForResume(@Param("now") Instant now, Pageable pageable);

    @Modifying
    @Query("UPDATE WorkflowExecution e SET e.status = 'FAILED', e.errorMessage = :msg, e.completedAt = :now " +
           "WHERE e.status = 'RUNNING' AND e.startedAt < :cutoff")
    int failStuckExecutions(@Param("cutoff") Instant cutoff, @Param("msg") String msg, @Param("now") Instant now);
}
