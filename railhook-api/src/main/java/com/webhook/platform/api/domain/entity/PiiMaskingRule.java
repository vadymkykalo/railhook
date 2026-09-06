package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.MaskStyle;
import com.webhook.platform.api.domain.enums.RuleType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pii_masking_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PiiMaskingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 20)
    private RuleType ruleType;

    @Column(name = "pattern_name", nullable = false, length = 100)
    private String patternName;

    @Column(name = "json_path", length = 500)
    private String jsonPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "mask_style", nullable = false, length = 20)
    @Builder.Default
    private MaskStyle maskStyle = MaskStyle.PARTIAL;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
