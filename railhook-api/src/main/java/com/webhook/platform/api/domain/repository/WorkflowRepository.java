package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    List<Workflow> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    boolean existsByProjectIdAndName(UUID projectId, String name);

    @Query("SELECT w FROM Workflow w WHERE w.projectId = :projectId AND w.enabled = true " +
           "AND w.triggerType = 'WEBHOOK_EVENT'")
    List<Workflow> findEnabledWebhookWorkflows(UUID projectId);
}
