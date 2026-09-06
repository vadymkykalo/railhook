package com.webhook.platform.api.config;

import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the stability of the operationIds in the published OpenAPI spec.
 *
 * <p>Springdoc names an operation after its Java method and, when two methods
 * across different controllers share a name, disambiguates them with a
 * positional {@code _1}, {@code _2}, ... suffix assigned in iteration order.
 * That order is not stable, so the same unchanged codebase emitted
 * {@code get_2} on one run and {@code get_4} on the next. Those ids are the
 * method names generated SDKs expose, and the committed {@code openapi.yaml} is
 * drift-checked in CI — an unstable id is a churning public API and a recurring
 * false-red build at the same time.
 *
 * <p>{@code OperationIdNamingConfig} replaces that behaviour with a
 * deterministic one, but it can only qualify a duplicate by its HTTP method —
 * which genuinely separates one handler mapped to several verbs, and does
 * nothing for two different controllers that both expose a {@code GET} named
 * {@code get}. Those have to be resolved at the source with an explicit
 * {@code @Operation(operationId = ...)}, and this test is what makes that
 * mandatory: it models how the effective id is derived and fails on any
 * collision the naming config cannot resolve on its own, so a new controller
 * method is caught here rather than surfacing later as spec churn.
 *
 * <p>A plain unit test on purpose: it scans bytecode, needs no Spring context or
 * database, and so belongs to the fast CI job.
 */
@Tag("ratchet")
class OpenApiOperationIdTest {

    private static final String CONTROLLER_PACKAGE = "com.webhook.platform.api.controller";

    private record Operations(String operationId, String declaredBy) {
    }

    @Test
    @DisplayName("every controller method maps to a unique, position-independent operationId")
    void operationIdsAreUniqueAndPositionIndependent() {
        Map<String, List<Operations>> byOperationId = new TreeMap<>();

        for (Class<?> controller : scanControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                RequestMapping mapping =
                        AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String signature = controller.getSimpleName() + "#" + method.getName();
                for (String operationId : effectiveOperationIds(method, mapping)) {
                    byOperationId
                            .computeIfAbsent(operationId, key -> new ArrayList<>())
                            .add(new Operations(operationId, signature));
                }
            }
        }

        assertThat(byOperationId)
                .as("controllers were found on the classpath")
                .isNotEmpty();

        Map<String, List<String>> collisions = new LinkedHashMap<>();
        byOperationId.forEach((operationId, declarations) -> {
            if (declarations.size() > 1) {
                collisions.put(operationId, declarations.stream().map(Operations::declaredBy).toList());
            }
        });

        assertThat(collisions)
                .as("""
                        Colliding operationIds. Springdoc would disambiguate these with a positional \
                        `_1`, `_2`, ... suffix whose assignment depends on controller-scan order, making \
                        the published spec and the generated SDK method names unstable. Give each listed \
                        method an explicit, descriptive @Operation(operationId = "...").""")
                .isEmpty();
    }

    @Test
    @DisplayName("no controller method hardcodes a positional operationId suffix")
    void explicitOperationIdsCarryNoPositionalSuffix() {
        List<String> suffixed = new ArrayList<>();

        for (Class<?> controller : scanControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                Operation operation = AnnotatedElementUtils.findMergedAnnotation(method, Operation.class);
                if (operation != null && operation.operationId().matches(".*_\\d+$")) {
                    suffixed.add(controller.getSimpleName() + "#" + method.getName()
                            + " -> " + operation.operationId());
                }
            }
        }

        assertThat(suffixed)
                .as("An explicit operationId ending in _<number> pins down what was originally a "
                        + "scan-order artifact; name the operation for what it does instead.")
                .isEmpty();
    }

    /**
     * Mirrors the id derivation: the explicit annotation value when set, otherwise the
     * method name — and, for a handler mapped to several verbs (which becomes one
     * operation per verb), the HTTP-method suffix
     * {@code OperationIdNamingConfig#deterministicOperationIds} appends to
     * separate them.
     */
    private static List<String> effectiveOperationIds(Method method, RequestMapping mapping) {
        Operation operation = AnnotatedElementUtils.findMergedAnnotation(method, Operation.class);
        String base = operation != null && !operation.operationId().isBlank()
                ? operation.operationId()
                : method.getName();

        RequestMethod[] verbs = mapping.method();
        if (verbs.length <= 1) {
            return List.of(base);
        }
        List<String> ids = new ArrayList<>(verbs.length);
        for (RequestMethod verb : verbs) {
            String lower = verb.name().toLowerCase(Locale.ROOT);
            ids.add(base + Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
        }
        return ids;
    }

    private static Set<Class<?>> scanControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<Class<?>> controllers = new java.util.LinkedHashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            controllers.add(ClassUtils.resolveClassName(
                    className, OpenApiOperationIdTest.class.getClassLoader()));
        }
        return controllers;
    }
}
