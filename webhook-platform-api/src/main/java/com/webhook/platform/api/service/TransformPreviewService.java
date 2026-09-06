package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.service.transform.TemplateTransformer;
import com.webhook.platform.api.dto.TransformPreviewRequest;
import com.webhook.platform.api.dto.TransformPreviewResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransformPreviewService {

    private final ObjectMapper objectMapper;
    private final TransformationRepository transformationRepository;
    private final TemplateTransformer templateTransformer;

    public TransformPreviewResponse preview(TransformPreviewRequest request) {
        List<String> errors = new ArrayList<>();
        String outputPayload = null;
        String outputHeaders = null;

        // Validate input JSON
        JsonNode root;
        try {
            root = objectMapper.readTree(request.getInputPayload());
        } catch (Exception e) {
            errors.add("Invalid input JSON: " + e.getMessage());
            return TransformPreviewResponse.builder()
                    .success(false)
                    .errors(errors)
                    .build();
        }

        // Resolve template: transformationId > template > transformExpression > passthrough
        String resolvedTemplate = null;

        if (request.getTransformationId() != null) {
            Transformation transformation = transformationRepository.findById(request.getTransformationId())
                    .orElse(null);
            if (transformation == null) {
                errors.add("Transformation not found: " + request.getTransformationId());
            } else {
                resolvedTemplate = transformation.getTemplate();
            }
        } else if (request.getTemplate() != null && !request.getTemplate().isBlank()) {
            resolvedTemplate = request.getTemplate();
        }

        if (!errors.isEmpty()) {
            return TransformPreviewResponse.builder()
                    .success(false)
                    .errors(errors)
                    .build();
        }

        if (resolvedTemplate != null) {
            // Full template-based transform with ${$.path} expressions
            try {
                JsonNode resultNode = templateTransformer.apply(resolvedTemplate, root);
                outputPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultNode);
            } catch (Exception e) {
                errors.add("Template transform error: " + e.getMessage());
            }
        } else if (request.getTransformExpression() != null && !request.getTransformExpression().isBlank()) {
            // Simple JSONPath pointer extraction ($.data → /data)
            try {
                String expr = request.getTransformExpression().trim();
                String pointer = expr;
                if (pointer.startsWith("$.")) {
                    pointer = "/" + pointer.substring(2).replace(".", "/");
                } else if (pointer.startsWith("$")) {
                    pointer = "";
                } else if (!pointer.startsWith("/")) {
                    pointer = "/" + pointer.replace(".", "/");
                }

                JsonNode result = pointer.isEmpty() ? root : root.at(pointer);
                if (result.isMissingNode()) {
                    errors.add("Expression matched no data: " + request.getTransformExpression());
                    outputPayload = "null";
                } else {
                    outputPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
                }
            } catch (Exception e) {
                errors.add("Transform error: " + e.getMessage());
            }
        } else {
            // No transform = passthrough
            try {
                outputPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            } catch (Exception e) {
                errors.add("JSON formatting error: " + e.getMessage());
            }
        }

        // Validate custom headers JSON
        if (request.getCustomHeaders() != null && !request.getCustomHeaders().isBlank()) {
            try {
                JsonNode headersNode = objectMapper.readTree(request.getCustomHeaders());
                if (!headersNode.isObject()) {
                    errors.add("Custom headers must be a JSON object");
                } else {
                    outputHeaders = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(headersNode);
                }
            } catch (Exception e) {
                errors.add("Invalid custom headers JSON: " + e.getMessage());
            }
        }

        return TransformPreviewResponse.builder()
                .outputPayload(outputPayload)
                .outputHeaders(outputHeaders)
                .success(errors.isEmpty())
                .errors(errors)
                .build();
    }
}
