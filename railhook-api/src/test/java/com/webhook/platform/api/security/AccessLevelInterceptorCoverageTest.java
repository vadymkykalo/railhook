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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// WebConfig registers ScopeEnforcementInterceptor for /api/** only; @RequireAccess elsewhere is decoration.
@Tag("ratchet")
class AccessLevelInterceptorCoverageTest {

    private static final String CONTROLLER_PACKAGE = "com.webhook.platform.api.controller";

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
                .sorted(Comparator.comparing(Class::getName))
                .toList();
    }
}
