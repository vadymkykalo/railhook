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
import java.util.UUID;

@Repository
public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, UUID> {

    Page<WorkflowExecution> findByWorkflowIdOrderByStartedAtDesc(UUID workflowId, Pageable pageable);

    long countByWorkflowIdAndStatus(UUID workflowId, WorkflowExecution.ExecutionStatus status);

    /** Counts for a whole page at once, so listing does not run one query per workflow per status. */
    @Query("SELECT e.workflowId, e.status, COUNT(e) FROM WorkflowExecution e "
            + "WHERE e.workflowId IN :workflowIds GROUP BY e.workflowId, e.status")
    List<Object[]> countByWorkflowIdsGroupedByStatus(@Param("workflowIds") Collection<UUID> workflowIds);

    boolean existsByWorkflowIdAndTriggerEventId(UUID workflowId, UUID triggerEventId);

    @Query("SELECT e FROM WorkflowExecution e WHERE e.status = 'RUNNING' AND e.startedAt < :cutoff")
    List<WorkflowExecution> findStuckExecutions(@Param("cutoff") Instant cutoff);

    /**
     * Suspended executions whose delay has expired, oldest first.
     *
     * <p>Backed by the partial index {@code idx_wf_exec_resume_due} (V065): almost every row in
     * this table is a finished execution that will never be WAITING again.
     *
     * <p>Note {@code findStuckExecutions} above matches RUNNING only. That is what makes a
     * five-minute delay safe: without it the recovery job could not tell a suspended execution
     * from a hung one and would fail every workflow that used a delay node.
     */
    @Query("SELECT e FROM WorkflowExecution e WHERE e.status = 'WAITING' AND e.resumeAt <= :now "
            + "ORDER BY e.resumeAt ASC")
    List<WorkflowExecution> findDueForResume(@Param("now") Instant now, Pageable pageable);

    @Modifying
    @Query("UPDATE WorkflowExecution e SET e.status = 'FAILED', e.errorMessage = :msg, e.completedAt = :now " +
           "WHERE e.status = 'RUNNING' AND e.startedAt < :cutoff")
    int failStuckExecutions(@Param("cutoff") Instant cutoff, @Param("msg") String msg, @Param("now") Instant now);
}
