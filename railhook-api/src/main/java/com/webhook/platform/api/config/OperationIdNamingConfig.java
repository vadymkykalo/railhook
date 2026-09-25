package com.webhook.platform.api.config;

import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.utils.Constants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Springdoc disambiguates duplicate operationIds with {@code _1}, {@code _2} suffixes in scan
 * order, which changes between runs. Here a duplicate is qualified by its HTTP method, and any
 * remaining one gets a suffix from path-sorted order, so the committed spec stays stable.
 *
 * <p>Registered under springdoc's own bean name so it replaces the default; under any other name
 * both customizers run and springdoc re-applies its positional suffixes.
 */
@Configuration
public class OperationIdNamingConfig {

    @Bean(name = Constants.GLOBAL_OPEN_API_CUSTOMIZER)
    public GlobalOpenApiCustomizer deterministicOperationIds() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            // Sorted so both passes below see the same order on every run.
            List<Map.Entry<String, Operation>> operations = new ArrayList<>();
            openApi.getPaths().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(pathEntry -> pathEntry.getValue().readOperationsMap().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(operationEntry -> operations.add(Map.entry(
                                    operationEntry.getKey().name(), operationEntry.getValue()))));

            Map<String, Integer> occurrences = new HashMap<>();
            for (Map.Entry<String, Operation> entry : operations) {
                String operationId = entry.getValue().getOperationId();
                if (operationId != null) {
                    occurrences.merge(operationId, 1, Integer::sum);
                }
            }

            for (Map.Entry<String, Operation> entry : operations) {
                Operation operation = entry.getValue();
                String operationId = operation.getOperationId();
                if (operationId == null || occurrences.getOrDefault(operationId, 0) <= 1) {
                    continue;
                }
                operation.setOperationId(operationId + capitalize(entry.getKey()));
            }

            // Two paths sharing both an id and a verb.
            Map<String, Integer> assigned = new HashMap<>();
            for (Map.Entry<String, Operation> entry : operations) {
                Operation operation = entry.getValue();
                String operationId = operation.getOperationId();
                if (operationId == null) {
                    continue;
                }
                int seen = assigned.merge(operationId, 1, Integer::sum);
                if (seen > 1) {
                    operation.setOperationId(operationId + "_" + (seen - 1));
                }
            }
        };
    }

    private static String capitalize(String httpMethod) {
        String lower = httpMethod.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
