package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Rule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RuleRepository extends JpaRepository<Rule, UUID> {

    List<Rule> findByProjectIdOrderByPriorityDescCreatedAtDesc(UUID projectId);

    @Query("SELECT r FROM Rule r LEFT JOIN FETCH r.actions WHERE r.projectId = :projectId AND r.enabled = true ORDER BY r.priority DESC")
    List<Rule> findEnabledWithActions(@Param("projectId") UUID projectId);

    boolean existsByProjectIdAndName(UUID projectId, String name);

    long countByProjectId(UUID projectId);
}
