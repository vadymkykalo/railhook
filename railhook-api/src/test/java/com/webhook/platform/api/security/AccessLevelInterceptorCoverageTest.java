package com.webhook.platform.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ratchet over the one thing that decides whether {@link RequireAccess} is enforced at all:
 * whether the request reaches {@link ScopeEnforcementInterceptor}.
 *
 * <p>{@code WebConfig} registers that interceptor with {@code addPathPatterns("/api/**")} and
 * nothing else. An annotation on a handler mapped outside that prefix is decoration — it reads
 * as a guard, {@code MutatingHandlerAccessDeclarationTest} counts it as a declaration, and no
 * check runs. Today every authenticated controller happens to live under {@code /api}; the three
 * that do not ({@code /ingress}, {@code /tunnel}, {@code /hook}) are unauthenticated by design and
 * carry no annotation. This freezes that coincidence into a rule.
 *
 * <p>{@code AccessLevelEnforcementTest} proves the interceptor enforces the annotation. This
 * proves the interceptor is reached. Both are needed: the first passes just as happily when the
 * handler under test is the only one on an intercepted path.
 *
 * <p>Deliberately a plain {@code *Test}: reflection over the classpath, no Spring context and no
 * container (see {@code scripts/check-test-routing.sh}).
 */
@Tag("ratchet")
class AccessLevelInterceptorCoverageTest {

    private static final String CONTROLLER_PACKAGE = "com.webhook.platform.api.controller";

    /** The single path pattern {@code WebConfig} registers the interceptor under. */
    private static final String INTERCEPTED_PREFIX = "/api/";

    @Test
    @DisplayName("every @RequireAccess handler is mapped under the prefix the interceptor covers")
    void annotatedHandlersAreOnAnInterceptedPath() {
        Set<String> annotated = new TreeSet<>();
        Set<String> unreachable = new TreeSet<>();

        for (Class<?> controller : findControllers()) {
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String classPath = firstPathOf(classMapping);
            boolean classLevelAccess = controller.isAnnotationPresent(RequireAccess.class);

            for (Method method : controller.getDeclaredMethods()) {
                if (!classLevelAccess && !method.isAnnotationPresent(RequireAccess.class)) {
                    continue;
                }
                RequestMapping methodMapping =
                        AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (methodMapping == null) {
                    continue;
                }
                String id = controller.getSimpleName() + "." + method.getName();
                annotated.add(id);

                String path = classPath + firstPathOf(methodMapping);
                if (!path.startsWith(INTERCEPTED_PREFIX)) {
                    unreachable.add(id + "  →  " + path);
                }
            }
        }

        assertTrue(annotated.size() > 50,
                "the scan found only " + annotated.size() + " handlers carrying @RequireAccess — the "
                        + "classpath scan is probably broken, which would make this test vacuous");

        assertEquals(Set.of(), unreachable,
                "These handlers declare an access level that nothing enforces: WebConfig registers "
                        + "ScopeEnforcementInterceptor on \"" + INTERCEPTED_PREFIX + "**\" only, and they are "
                        + "mapped outside it. Either map them under /api, or widen the registration in "
                        + "WebConfig and update INTERCEPTED_PREFIX here — do not leave the annotation as "
                        + "decoration.");
    }

    /**
     * The first path a mapping declares, normalised without its trailing slash. Good enough: this
     * test asks which prefix a handler lives under, not what its exact URI template is, and no
     * controller here declares paths that straddle {@code /api}.
     */
    private static String firstPathOf(RequestMapping mapping) {
        if (mapping == null) {
            return "";
        }
        String[] paths = mapping.path().length > 0 ? mapping.path() : mapping.value();
        if (paths.length == 0 || paths[0].isEmpty()) {
            return "";
        }
        String path = paths[0];
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private List<Class<?>> findControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return scanner.findCandidateComponents(CONTROLLER_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .<Class<?>>map(name -> {
                    try {
                        return Class.forName(name);
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Scanned but could not load " + name, e);
                    }
                })
                .sorted(java.util.Comparator.comparing(Class::getName))
                .toList();
    }
}
