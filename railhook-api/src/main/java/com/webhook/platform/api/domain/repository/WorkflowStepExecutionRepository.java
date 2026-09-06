package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.WorkflowStepExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowStepExecutionRepository extends JpaRepository<WorkflowStepExecution, UUID> {

    List<WorkflowStepExecution> findByExecutionIdOrderByCreatedAtAsc(UUID executionId);
}
