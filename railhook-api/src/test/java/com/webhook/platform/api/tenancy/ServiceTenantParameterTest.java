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

/**
 * Ratchet over the last of the four authorization questions.
 *
 * <p>"Is this row inside the caller's organization?" used to be answered by an
 * {@code organizationId} parameter threaded through ~186 service method signatures, with a
 * {@code validateProjectOwnership} call in each body that an author could simply not write. It is
 * now a property of data access: {@code @TenantId} puts the predicate on every query and
 * {@link TenantContext} says whose data the thread is looking at.
 *
 * <p>That only stays true if the parameter does not come back. A method that takes an
 * organization is a method whose caller chooses one, which is the shape the ADR removed — so a
 * new one fails the build unless it is listed below with a reason.
 *
 * <p>The third sibling of {@code MutatingHandlerScopeDeclarationTest} and
 * {@code MutatingHandlerAccessDeclarationTest}, and deliberately the same shape: reflection, a
 * frozen exemption list, and a vacuity guard so a scan that silently finds nothing cannot pass.
 *
 * <p>Deliberately a plain {@code *Test}: pure reflection over the classpath, so it runs in the
 * no-Docker unit job — see {@code scripts/check-test-routing.sh}. The companion that proves the
 * filter actually confines rows is {@code CrossTenantIsolationTest}, which needs a database.
 */
@Tag("ratchet")
class ServiceTenantParameterTest {

    private static final String SERVICE_PACKAGE = "com.webhook.platform.api.service";

    private static final List<String> TENANT_PARAMETER_NAMES = List.of("organizationId", "orgId");

    /**
     * Public service methods that legitimately take an organization, with the reason each is
     * acceptable. Format is {@code SimpleClassName.methodName}.
     *
     * <p>The bar: the organization comes off a <em>row being processed</em>, not off the caller.
     * That is the case for system-scoped work, which walks many organizations and has no ambient
     * one, and for a cache keyed by organization. Anything reachable from a request should read
     * {@link TenantContext} instead — adding an entry here for one puts the check back in the
     * caller's hands, which is what structural tenancy exists to stop.
     */
    private static final Set<String> DOCUMENTED_EXEMPTIONS = new TreeSet<>(Set.of(
            // Plan lookup and its cache. forProject resolves a Project and reads the organization
            // off it, on paths that may be running as the system tenant; the billing schedulers
            // evict the cache for an organization they are processing, not one they are "in".
            // forCurrentTenant and the no-argument overloads are the request-facing ones and read
            // the tenant scope.
            "PlanLookup.forOrganization",
            "PlanLookup.evict",
            "EntitlementService.getPlan",
            "EntitlementService.getRateLimit",
            "EntitlementService.evictPlanCache",

            // Accepting an invite is cross-organization by construction: the invitee's own tenant
            // is a different organization from the one whose Membership row they are accepting, so
            // the {orgId} path variable is the subject and the method runs as the system tenant.
            "MembershipService.acceptInvite",

            // Outbound calls to a payment provider. The organization is part of the request being
            // built for an external system, not a tenancy decision this process makes.
            "BillingProvider.createCustomer",
            "StripeBillingProvider.createCustomer",

            // The operator back-office, for the same reason as acceptInvite above: the
            // {organizationId} in the path is the subject being administered, and the caller is
            // the platform-admin credential, which belongs to no organization at all. There is
            // no ambient tenant to read here - reading one would mean the operator could only
            // ever act on their own, which is the opposite of what a back-office is. Each method
            // is @SystemTenant and says so.
            "PlatformAdminService.getOrganization",
            "PlatformAdminService.suspend",
            "PlatformAdminService.reinstate",

            // Same subject-not-caller reason, with one addition worth stating: getUsage does not
            // query anything itself. It enters the subject's tenant scope with callAs and asks
            // the service the tenant's own billing page asks, so the operator and the customer
            // read one set of figures rather than two implementations of the same question.
            "PlatformAdminService.getUsage",

            // Suspension lookup and its cache, exactly parallel to PlanLookup above: asked about
            // an organization by whoever holds its id - the request filter chain, before any
            // handler, and the operator evicting after a change.
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
        // Interfaces are included on purpose: BillingProvider declares createCustomer(UUID, ...)
        // and the default scanner would skip it, quietly shrinking what this ratchet covers.
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

    /**
     * Excludes test doubles that share the scanned package.
     *
     * <p>The classpath scan cannot tell {@code TestBillingProvider} from the real one, and a stub
     * that takes an organization is not a tenancy decision anybody ships. Filtering on the code
     * source keeps the ratchet pointed at production code without an exemption entry that would
     * also pre-approve a real method of that name.
     */
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
