package com.webhook.platform.api.tenancy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A method taking an organization lets its caller choose one; new ones fail unless listed with a reason.
@Tag("ratchet")
class ServiceTenantParameterTest {

    private static final String SERVICE_PACKAGE = "com.webhook.platform.api.service";

    private static final List<String> TENANT_PARAMETER_NAMES = List.of("organizationId", "orgId");

    // The bar: the organization comes off a row being processed, not off the caller.
    private static final Set<String> DOCUMENTED_EXEMPTIONS = new TreeSet<>(Set.of(
            // A Redis key, not data access; the caller reads TenantContext and refuses the system tenant.
            "RedisRateLimiterService.tryAcquireForOrganization",

            // The organizations come off the erased person's membership rows, not the request.
            "OrganizationService.deleteOrganizationById",
            // Already inside the erasure's transaction, where entering a scope is refused.
            "TunnelService.closeSessionsOfOrganization",

            "PlanLookup.forOrganization",
            "PlanLookup.evict",
            "EntitlementService.getPlan",
            "EntitlementService.getRateLimit",
            "EntitlementService.evictPlanCache",

            // Cross-organization by construction: the {orgId} path variable is the subject.
            "MembershipService.acceptInvite",

            // Outbound to a payment provider: not a tenancy decision this process makes.
            "BillingProvider.createCustomer",
            "StripeBillingProvider.createCustomer",

            // Back-office: the path's organization is the subject; the platform admin belongs to none.
            "PlatformAdminService.getOrganization",
            "PlatformAdminService.suspend",
            "PlatformAdminService.reinstate",

            // Enters the subject's scope and asks what the tenant's own billing page asks.
            "PlatformAdminService.getUsage",

            // Each enters the subject's scope and lets @TenantId confine the query.
            "PlatformAdminService.listMembers",
            "PlatformAdminService.listProjects",
            "PlatformAdminService.listAuditLog",

            // Parallel to PlanLookup: asked by whoever holds the id.
            "SuspensionLookup.forOrganization",
            "SuspensionLookup.suspensionReason",
            "SuspensionLookup.evict"
    ));

    @Test
    @DisplayName("no public service method takes an organization as a parameter")
    void serviceMethodsDoNotTakeAnOrganization() {
        Set<String> offenders = new TreeSet<>();
        int methodsScanned = 0;
        int classesScanned = 0;

        for (Class<?> serviceClass : serviceClasses()) {
            classesScanned++;
            for (Method method : serviceClass.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                    continue;
                }
                methodsScanned++;
                if (takesTenantParameter(method)) {
                    String id = serviceClass.getSimpleName() + "." + method.getName();
                    if (!DOCUMENTED_EXEMPTIONS.contains(id)) {
                        offenders.add(id);
                    }
                }
            }
        }

        // Vacuity guard: a scan that finds nothing would pass this test while checking nothing.
        assertTrue(classesScanned >= 40,
                "Expected to scan at least 40 service classes, found " + classesScanned
                        + " — the classpath scan is broken, not the code");
        assertTrue(methodsScanned >= 300,
                "Expected to scan at least 300 public service methods, found " + methodsScanned);

        assertEquals(Set.of(), offenders,
                "These service methods take an organization as a parameter. Org "
                        + "ownership a property of data access: read TenantContext, or enter a scope "
                        + "with TenantContext.runAs / @SystemTenant. If the organization genuinely "
                        + "comes off a row rather than off the caller, add the method to "
                        + "DOCUMENTED_EXEMPTIONS with a reason.");
    }

    @Test
    @DisplayName("every exemption still names a real method")
    void exemptionsAreNotStale() {
        Set<String> live = new TreeSet<>();
        for (Class<?> serviceClass : serviceClasses()) {
            for (Method method : serviceClass.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()
                        && takesTenantParameter(method)) {
                    live.add(serviceClass.getSimpleName() + "." + method.getName());
                }
            }
        }
        Set<String> stale = new TreeSet<>(DOCUMENTED_EXEMPTIONS);
        stale.removeAll(live);
        assertEquals(Set.of(), stale,
                "These exemptions no longer match any method that takes an organization. Delete "
                        + "them — a stale entry silently pre-approves a future method of the same name.");
    }

    private static boolean takesTenantParameter(Method method) {
        for (Parameter parameter : method.getParameters()) {
            if (parameter.getType() != UUID.class) {
                continue;
            }
            // -parameters is on for this build (see the root pom), so these are the real names.
            if (TENANT_PARAMETER_NAMES.contains(parameter.getName())) {
                return true;
            }
        }
        return false;
    }

    private static List<Class<?>> serviceClasses() {
        // Interfaces too: BillingProvider declares createCustomer(UUID, ...), which the default scanner skips.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return true;
                    }
                };
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
        return scanner.findCandidateComponents(SERVICE_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(ServiceTenantParameterTest::load)
                .filter(ServiceTenantParameterTest::isProductionClass)
                .toList();
    }

    // A classpath scan cannot tell TestBillingProvider from the real one.
    private static boolean isProductionClass(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return true;
        }
        return !source.getLocation().getPath().contains("test-classes");
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Scanned class is not loadable: " + name, e);
        }
    }
}
