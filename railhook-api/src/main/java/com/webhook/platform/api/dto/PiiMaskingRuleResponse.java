package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.entity.PiiMaskingRule;
import com.webhook.platform.api.domain.enums.MaskStyle;
import com.webhook.platform.api.domain.enums.RuleType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PiiMaskingRuleResponse {
    private UUID id;
    private UUID projectId;
    private RuleType ruleType;
    private String patternName;
    private String jsonPath;
    private MaskStyle maskStyle;
    private Boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    public static PiiMaskingRuleResponse of(PiiMaskingRule rule) {
        return PiiMaskingRuleResponse.builder()
                .id(rule.getId())
                .projectId(rule.getProjectId())
                .ruleType(rule.getRuleType())
                .patternName(rule.getPatternName())
                .jsonPath(rule.getJsonPath())
                .maskStyle(rule.getMaskStyle())
                .enabled(rule.getEnabled())
                .createdAt(rule.getCreatedAt())
                .updatedAt(rule.getUpdatedAt())
                .build();
    }
}
