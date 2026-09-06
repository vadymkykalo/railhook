package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.RuleExecutionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface RuleExecutionLogRepository extends JpaRepository<RuleExecutionLog, UUID> {

    Page<RuleExecutionLog> findByRuleIdOrderByExecutedAtDesc(UUID ruleId, Pageable pageable);

    Page<RuleExecutionLog> findByProjectIdOrderByExecutedAtDesc(UUID projectId, Pageable pageable);

    long countByRuleIdAndMatchedTrue(UUID ruleId);

    long countByRuleId(UUID ruleId);

    /** Executions and matches for a whole page at once. */
    @Query("SELECT l.ruleId, COUNT(l), SUM(CASE WHEN l.matched = true THEN 1 ELSE 0 END) "
            + "FROM RuleExecutionLog l WHERE l.ruleId IN :ruleIds GROUP BY l.ruleId")
    List<Object[]> countByRuleIds(@Param("ruleIds") Collection<UUID> ruleIds);

    @Modifying
    @Query("DELETE FROM RuleExecutionLog l WHERE l.executedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
