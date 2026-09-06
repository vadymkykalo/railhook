package com.webhook.platform.worker.domain.repository;

import com.webhook.platform.worker.domain.entity.IncomingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface IncomingEventRepository extends JpaRepository<IncomingEvent, UUID> {
}
