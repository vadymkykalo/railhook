package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlanRepository extends JpaRepository<Plan, UUID> {

    Optional<Plan> findByName(String name);

    List<Plan> findByActiveTrueOrderByPriceMonthlyCentsAsc();
}
