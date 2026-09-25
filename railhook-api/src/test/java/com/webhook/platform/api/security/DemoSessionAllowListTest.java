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
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Adding a handler here is a security decision made in review, never a way to get green.
@Tag("ratchet")
class DemoSessionAllowListTest {

    private static final String CONTROLLER_PACKAGE = "com.webhook.platform.api.controller";

    private static final Set<String> ALLOWED = Set.of(
            // Ends the caller's own demo session.
            "AuthController.logout",
            // Replaces an expired demo session.
            "PublicDemoController.createSession",
            // The public site's anonymous forms.
            "PublicContactController.send",
            "PublicBinController.create",
            // Runs the script in the sandbox; nothing is stored.
            "TransformPreviewController.preview",
            // Declares WRITE, but DemoDryRunMask removes the signature for the demo.
            "TransformPreviewController.deliveryDryRun");

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

    // Named rather than left to the set: moving the annotation one method down would expose the store.
    @Test
    @DisplayName("the demo may run a transformation, in both places the product runs one")
    void theDemoMayRunATransformation() {
        assertTrue(isAllowedInDemo("TransformPreviewController", "preview"),
                "the Transform Studio's preview is how the demo shows what a script does");
        assertTrue(isAllowedInDemo("TransformPreviewController", "deliveryDryRun"),
                "the dry-run shows the bytes a real Delivery would carry; the demo's copy is masked");
    }

    @Test
    @DisplayName("the demo may not create, change or delete a transformation")
    void theDemoMayNotKeepATransformation() {
        for (String handler : new String[]{"create", "update", "delete"}) {
            assertFalse(isAllowedInDemo("TransformationController", handler),
                    "TransformationController." + handler + " writes to the demo organization's "
                            + "transformation store; running a script is not saving one");
        }
    }

    private static boolean isAllowedInDemo(String controller, String handler) {
        List<Method> matches = controllers().stream()
                .filter(type -> type.getSimpleName().equals(controller))
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.getName().equals(handler))
                .toList();
        assertEquals(1, matches.size(),
                "expected exactly one " + controller + "." + handler + ", found " + matches.size()
                        + " — the handler this case pins was renamed or overloaded");
        return matches.get(0).isAnnotationPresent(AllowedInDemo.class);
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
