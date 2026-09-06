package com.webhook.platform.worker.domain.repository;

import com.webhook.platform.worker.domain.entity.IncomingDestination;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IncomingDestinationRepository extends JpaRepository<IncomingDestination, UUID> {

    List<IncomingDestination> findByIncomingSourceIdAndEnabledTrue(UUID incomingSourceId);
}
