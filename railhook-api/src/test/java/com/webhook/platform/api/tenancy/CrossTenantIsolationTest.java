package com.webhook.platform.api.tenancy;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the tenant filter actually confines rows.
 *
 * <p>{@code ServiceTenantParameterTest} is the ratchet that keeps {@code organizationId} out of
 * service signatures; it says nothing about whether anything is enforced. This is the other half:
 * two organizations, real rows, a real database, and the question the ADR is about — can a caller
 * scoped to one reach the other's data?
 *
 * <p>Named {@code *IsolationTest} so it routes to the Docker-backed integration job — see
 * {@code scripts/check-test-routing.sh}.
 */
class CrossTenantIsolationTest extends AbstractIntegrationTest {

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private EndpointRepository endpointRepository;

    private UUID orgA;
    private UUID orgB;
    private UUID projectA;
    private UUID projectB;
    private UUID endpointA;

    @BeforeEach
    void seedTwoOrganizations() {
        // Organizations are not tenant-scoped -- they are the tenant -- but everything below is,
        // so the fixture is built under the system scope.
        TenantContext.runAsSystem(() -> {
            var plan = planRepository.findAll().stream().findFirst().orElseThrow(
                    () -> new IllegalStateException("Migrations seed at least one plan"));
            orgA = organizationRepository.save(Organization.builder().name("A").plan(plan).build()).getId();
            orgB = organizationRepository.save(Organization.builder().name("B").plan(plan).build()).getId();
        });

        TenantContext.runAs(orgA, () -> {
            projectA = projectRepository.save(
                    Project.builder().name("proj-a").organizationId(orgA).build()).getId();
            endpointA = endpointRepository.save(endpoint(projectA)).getId();
        });

        TenantContext.runAs(orgB, () ->
                projectB = projectRepository.save(
                        Project.builder().name("proj-b").organizationId(orgB).build()).getId());
    }

    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("findById cannot reach another organization's row")
    void findByIdIsConfinedToTheTenant() {
        TenantContext.runAs(orgB, () -> {
            // The id is correct and the row exists. It is simply not this tenant's.
            assertThat(projectRepository.findById(projectA)).isEmpty();
            assertThat(endpointRepository.findById(endpointA)).isEmpty();

            assertThat(projectRepository.findById(projectB)).isPresent();
        });
    }

    @Test
    @DisplayName("a derived query returns only the caller's rows")
    void derivedQueriesAreConfinedToTheTenant() {
        TenantContext.runAs(orgA, () -> {
            List<Project> visible = projectRepository.findAll();
            assertThat(visible).extracting(Project::getId).containsExactly(projectA);
        });
    }

    @Test
    @DisplayName("the system scope sees every organization")
    void systemScopeIsUnfiltered() {
        TenantContext.runAsSystem(() -> {
            assertThat(projectRepository.findById(projectA)).isPresent();
            assertThat(projectRepository.findById(projectB)).isPresent();
        });
    }

    @Test
    @DisplayName("an insert is stamped with the current tenant, not with what the caller passed")
    void insertsAreStampedWithTheCurrentTenant() {
        UUID id = TenantContext.callAs(orgB, () ->
                projectRepository.save(Project.builder().name("stamped").build()).getId());

        TenantContext.runAsSystem(() ->
                assertThat(projectRepository.findById(id))
                        .get()
                        .extracting(Project::getOrganizationId)
                        .isEqualTo(orgB));
    }

    @Test
    @DisplayName("writing another organization's id is refused rather than silently accepted")
    void writingAcrossTenantsIsRefused() {
        assertThatThrownBy(() -> TenantContext.runAs(orgB, () ->
                projectRepository.saveAndFlush(
                        Project.builder().name("smuggled").organizationId(orgA).build())))
                .hasMessageContaining("tenant");
    }

    @Test
    @DisplayName("an unscoped thread fails loudly instead of reading everything")
    void noScopeIsAFailure() {
        TenantContext.clear();
        assertThatThrownBy(() -> projectRepository.findById(projectA))
                .hasRootCauseInstanceOf(TenantNotResolvedException.class);
    }

    private static Endpoint endpoint(UUID projectId) {
        return Endpoint.builder()
                .projectId(projectId)
                .url("https://example.test/hook")
                .secretEncrypted("x")
                .secretIv("y")
                .encryptionKeyVersion(1)
                .enabled(true)
                .createdAt(Instant.now())
                .build();
    }
}
