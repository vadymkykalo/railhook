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
 * Makes the operationIds in the published OpenAPI spec independent of
 * controller-scan order.
 *
 * <p>Springdoc names an operation after its Java method and, when two methods
 * across different controllers share a name, ships its own
 * {@code OperationIdCustomizer} that disambiguates them by appending {@code _1},
 * {@code _2}, ... in iteration order. That order is not stable, so the same
 * unchanged codebase emits {@code get_2} on one run and {@code get_4} on the
 * next. Those ids are the method names generated SDKs expose and the committed
 * {@code openapi.yaml} is drift-checked in CI, so an unstable id is a churning
 * public API and a recurring false-red build at once.
 *
 * <p>This bean is registered under springdoc's own
 * {@link Constants#GLOBAL_OPEN_API_CUSTOMIZER} name, which is how its default is
 * declared ({@code @ConditionalOnMissingBean(name = GLOBAL_OPEN_API_CUSTOMIZER)}) —
 * registering under any other name leaves both customizers active in an
 * unordered set, and springdoc's would simply re-apply positional suffixes on
 * top of whatever this one did.
 *
 * <p>Disambiguation here is derived from the operation itself rather than from
 * iteration order: a duplicated id is qualified by its HTTP method (which is
 * what actually distinguishes the tunnel and capture ingress endpoints, where
 * one handler is legitimately mapped to seven verbs), and any residual
 * duplicate falls back to a suffix assigned over a path-sorted list, which is
 * stable across runs. The residual case is not expected to occur: cross-
 * controller collisions are meant to be resolved at the source with an explicit
 * {@code @Operation(operationId = ...)}, and {@code OpenApiOperationIdTest}
 * fails the build when one is left unresolved.
 */
@Configuration
public class OperationIdNamingConfig {

    @Bean(name = Constants.GLOBAL_OPEN_API_CUSTOMIZER)
    public GlobalOpenApiCustomizer deterministicOperationIds() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            // Sorted by path, then by HTTP method, so every pass below sees the
            // same sequence regardless of how the paths map happens to iterate.
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

            // Residual collisions (two paths sharing both an id and a verb) get a
            // suffix from the sorted order above rather than from iteration order.
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
