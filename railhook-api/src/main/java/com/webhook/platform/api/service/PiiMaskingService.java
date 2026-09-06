package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.PiiMaskingRule;
import com.webhook.platform.api.domain.enums.MaskStyle;
import com.webhook.platform.api.domain.enums.RuleType;
import com.webhook.platform.api.domain.repository.PiiMaskingRuleRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.PiiMaskingRuleRequest;
import com.webhook.platform.api.dto.PiiMaskingRuleResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.util.PiiSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PiiMaskingService {

    private final PiiMaskingRuleRepository ruleRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public List<PiiMaskingRuleResponse> listRules(UUID projectId) {
        validateProjectAccess(projectId);
        return ruleRepository.findByProjectId(projectId).stream()
                .map(PiiMaskingRuleResponse::of)
                .collect(Collectors.toList());
    }

    @Transactional
    public PiiMaskingRuleResponse createRule(UUID projectId, PiiMaskingRuleRequest request) {
        validateProjectAccess(projectId);

        boolean isBuiltin = isBuiltinPattern(request.getPatternName());

        PiiMaskingRule rule = PiiMaskingRule.builder()
                .projectId(projectId)
                .ruleType(isBuiltin ? RuleType.BUILTIN : RuleType.CUSTOM)
                .patternName(request.getPatternName())
                .jsonPath(request.getJsonPath())
                .maskStyle(request.getMaskStyle())
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .build();

        rule = ruleRepository.save(rule);
        log.info("Created PII masking rule '{}' for project {}", rule.getPatternName(), projectId);
        return PiiMaskingRuleResponse.of(rule);
    }

    @Transactional
    public PiiMaskingRuleResponse updateRule(UUID projectId, UUID ruleId, PiiMaskingRuleRequest request) {
        validateProjectAccess(projectId);

        PiiMaskingRule rule = ruleRepository.findById(ruleId)
                .filter(r -> r.getProjectId().equals(projectId))
                .orElseThrow(() -> new NotFoundException("Masking rule not found"));

        if (request.getMaskStyle() != null) {
            rule.setMaskStyle(request.getMaskStyle());
        }
        if (request.getJsonPath() != null) {
            rule.setJsonPath(request.getJsonPath());
        }
        if (request.getEnabled() != null) {
            rule.setEnabled(request.getEnabled());
        }

        rule = ruleRepository.save(rule);
        log.info("Updated PII masking rule '{}' for project {}", rule.getPatternName(), projectId);
        return PiiMaskingRuleResponse.of(rule);
    }

    @Transactional
    public void deleteRule(UUID projectId, UUID ruleId) {
        validateProjectAccess(projectId);

        PiiMaskingRule rule = ruleRepository.findById(ruleId)
                .filter(r -> r.getProjectId().equals(projectId))
                .orElseThrow(() -> new NotFoundException("Masking rule not found"));

        ruleRepository.delete(rule);
        log.info("Deleted PII masking rule '{}' from project {}", rule.getPatternName(), projectId);
    }

    @Transactional
    public void seedDefaultRules(UUID projectId) {
        if (!ruleRepository.findByProjectId(projectId).isEmpty()) {
            return;
        }

        List<PiiMaskingRule> defaults = List.of(
                PiiMaskingRule.builder()
                        .projectId(projectId).ruleType(RuleType.BUILTIN)
                        .patternName("email").maskStyle(MaskStyle.PARTIAL).enabled(true).build(),
                PiiMaskingRule.builder()
                        .projectId(projectId).ruleType(RuleType.BUILTIN)
                        .patternName("phone").maskStyle(MaskStyle.PARTIAL).enabled(true).build(),
                PiiMaskingRule.builder()
                        .projectId(projectId).ruleType(RuleType.BUILTIN)
                        .patternName("card").maskStyle(MaskStyle.PARTIAL).enabled(true).build()
        );
        ruleRepository.saveAll(defaults);
        log.info("Seeded default PII masking rules for project {}", projectId);
    }

    /**
     * Sanitizes a JSON payload by applying all enabled rules for the project.
     */
    @Transactional(readOnly = true)
    public String sanitizePayload(UUID projectId, String payload) {
        List<PiiMaskingRule> rules = ruleRepository.findByProjectIdAndEnabledTrue(projectId);
        if (rules.isEmpty()) {
            return payload;
        }

        List<PiiSanitizer.Rule> sanitizerRules = rules.stream()
                .map(r -> new PiiSanitizer.Rule(
                        r.getPatternName(),
                        r.getJsonPath(),
                        toSanitizerMaskStyle(r.getMaskStyle()),
                        r.getEnabled()
                ))
                .collect(Collectors.toList());

        return PiiSanitizer.sanitize(payload, sanitizerRules);
    }

    private PiiSanitizer.MaskStyle toSanitizerMaskStyle(MaskStyle style) {
        return switch (style) {
            case FULL -> PiiSanitizer.MaskStyle.FULL;
            case PARTIAL -> PiiSanitizer.MaskStyle.PARTIAL;
            case HASH -> PiiSanitizer.MaskStyle.HASH;
        };
    }

    private boolean isBuiltinPattern(String name) {
        return PiiSanitizer.BUILTIN_EMAIL.equals(name)
                || PiiSanitizer.BUILTIN_PHONE.equals(name)
                || PiiSanitizer.BUILTIN_CARD.equals(name);
    }

    private void validateProjectAccess(UUID projectId) {
        UUID organizationId = TenantContext.require();
        projectRepository.findById(projectId)
                .filter(p -> p.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }

}
