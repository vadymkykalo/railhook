package com.webhook.platform.worker.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ordering_cursors")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderingCursor {

    @Id
    @Column(name = "endpoint_id")
    private UUID endpointId;

    @Column(name = "last_delivered_sequence", nullable = false)
    private Long lastDeliveredSequence;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
