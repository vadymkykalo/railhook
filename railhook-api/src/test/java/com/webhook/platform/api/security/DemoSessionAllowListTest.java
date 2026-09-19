package com.webhook.platform.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ratchet over the handlers a public demo session may change something through.
 *
 * <p>A demo token goes to anyone who asks for one, and {@link AllowedInDemo} is the only way past
 * the rule that it changes nothing. So the set is frozen here: adding a handler to it is a
 * security decision made in review, with the reason written on the annotation, never a way to get
 * a test green. {@code DemoSessionIntegrationTest} proves every other state-changing handler
 * refuses a real demo session.
 *
 * <p>Deliberately a plain {@code *Test}: reflection over the classpath, no container.
 */
@Tag("ratchet")
class DemoSessionAllowListTest {

    private static final String CONTROLLER_PACKAGE = "com.webhook.platform.api.controller";

    private static final Set<String> ALLOWED = Set.of(
            // Ends the caller's own demo session; the demo branch leaves the browser's cookie alone.
            "AuthController.logout",
            // Opens a new demo session, so an expired one can be replaced.
            "PublicDemoController.createSession",
            // The public site's anonymous forms, usable by a visitor who still holds a demo token.
            "PublicContactController.send",
            "PublicBinController.create");

    @Test
    @DisplayName("the handlers a demo session may write through are exactly the reviewed ones")
    void allowListIsFrozen() {
        Set<String> found = new TreeSet<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (method.isAnnotationPresent(AllowedInDemo.class)) {
                    found.add(controller.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertEquals(new TreeSet<>(ALLOWED), found,
                "@AllowedInDemo lets anyone on the internet call a handler with the demo's identity. "
                        + "Adding one is a review decision: update this list with the reason, or remove the annotation.");
    }

    @Test
    @DisplayName("@RefusedInDemo is only on reads, where the method-based rule would let it through")
    void refusedInDemoMarksReads() {
        Set<String> misplaced = new TreeSet<>();
        int marked = 0;
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(RefusedInDemo.class)) {
                    continue;
                }
                marked++;
                if (!method.isAnnotationPresent(GetMapping.class)) {
                    misplaced.add(controller.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertTrue(marked >= 2, "the exports lost their @RefusedInDemo");
        assertEquals(Set.of(), misplaced, "a write is already refused; @RefusedInDemo there says nothing");
    }

    private static List<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> found = scanner.findCandidateComponents(CONTROLLER_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .<Class<?>>map(name -> {
                    try {
                        return Class.forName(name);
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Scanned but could not load " + name, e);
                    }
                })
                .toList();
        assertTrue(found.size() > 30, "the classpath scan found only " + found.size() + " controllers");
        return found;
    }
}
