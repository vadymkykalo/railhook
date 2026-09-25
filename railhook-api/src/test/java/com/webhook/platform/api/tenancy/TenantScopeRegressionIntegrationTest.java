package com.webhook.platform.api.tenancy;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.audit.AuditLogAspect;
import com.webhook.platform.api.domain.entity.AuditLog;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.entity.Workflow;
import com.webhook.platform.api.domain.entity.WorkflowExecution;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.AuditLogRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.domain.repository.WorkflowExecutionRepository;
import com.webhook.platform.api.domain.repository.WorkflowRepository;
import com.webhook.platform.api.dto.OrganizationResponse;
import com.webhook.platform.api.service.OrganizationService;
import com.webhook.platform.api.service.workflow.WorkflowTriggerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Three tenancy failures that were silent: workflow executions, the audit log, the organization list.
class TenantScopeRegressionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowExecutionRepository workflowExecutionRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    @Autowired private WorkflowTriggerService workflowTriggerService;
    @Autowired private OrganizationService organizationService;
    @Autowired private AuditLogAspect auditLogAspect;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID orgA;
    private UUID orgB;
    private UUID projectA;
    private UUID userId;

    @BeforeEach
    void seed() {
        TenantContext.runAsSystem(() -> {
            var plan = planRepository.findAll().stream().findFirst().orElseThrow(
                    () -> new IllegalStateException("Migrations seed at least one plan"));
            orgA = organizationRepository.save(Organization.builder().name("regression-a").plan(plan).build()).getId();
            orgB = organizationRepository.save(Organization.builder().name("regression-b").plan(plan).build()).getId();

            userId = userRepository.save(User.builder()
                    .email("two-orgs-" + UUID.randomUUID() + "@example.test")
                    .fullName("Two Orgs")
                    .passwordHash("x")
                    .status(UserStatus.ACTIVE)
                    .build()).getId();
        });

        TenantContext.runAs(orgA, () -> {
            projectA = projectRepository.save(
                    Project.builder().name("regression-proj").organizationId(orgA).build()).getId();
            membershipRepository.save(Membership.builder()
                    .userId(userId).organizationId(orgA).role(MembershipRole.OWNER).build());
        });

        TenantContext.runAs(orgB, () ->
                membershipRepository.save(Membership.builder()
                        .userId(userId).organizationId(orgB).role(MembershipRole.DEVELOPER).build()));
    }

    @Test
    @DisplayName("a workflow triggered by the system-scoped poller is stamped with the workflow's organization")
    void workflowExecutionGetsTheWorkflowsOrganization() {
        UUID workflowId = TenantContext.callAs(orgA, () -> workflowRepository.save(Workflow.builder()
                .projectId(projectA)
                .name("regression-workflow")
                .enabled(true)
                .definition("{\"nodes\":[],\"edges\":[]}")
                .triggerConfig("{}")
                .build()).getId());

        UUID eventId = UUID.randomUUID();

        // As WorkflowTriggerOutboxService.poll calls it: only the system scope, no ambient organization.
        TenantContext.runAsSystem(() -> workflowTriggerService.triggerWorkflowsSync(
                projectA, eventId, "order.created", "{\"id\":1}", 0));

        TenantContext.runAsSystem(() -> {
            List<WorkflowExecution> executions = workflowExecutionRepository.findAll().stream()
                    .filter(e -> workflowId.equals(e.getWorkflowId()))
                    .toList();
            assertThat(executions)
                    .as("the trigger wrote no execution row at all — the NOT NULL violation was "
                            + "being swallowed as a duplicate")
                    .hasSize(1);
            assertThat(executions.get(0).getOrganizationId()).isEqualTo(orgA);
        });
    }

    @Test
    @DisplayName("the execution row is reachable from its own organization, not only from the system scope")
    void workflowExecutionIsVisibleToItsTenant() {
        UUID workflowId = TenantContext.callAs(orgA, () -> workflowRepository.save(Workflow.builder()
                .projectId(projectA)
                .name("regression-workflow-visible")
                .enabled(true)
                .definition("{\"nodes\":[],\"edges\":[]}")
                .triggerConfig("{}")
                .build()).getId());

        TenantContext.runAsSystem(() -> workflowTriggerService.triggerWorkflowsSync(
                projectA, UUID.randomUUID(), "order.created", "{\"id\":2}", 0));

        // A row stamped with the sentinel would be invisible here even though it exists.
        TenantContext.runAs(orgA, () ->
                assertThat(workflowExecutionRepository.findAll())
                        .extracting(WorkflowExecution::getWorkflowId)
                        .contains(workflowId));
    }

    @Test
    @DisplayName("the audit writer thread, which starts with no tenant scope, still writes the row")
    void auditLogIsWrittenFromAnUnscopedThread() throws Exception {
        UUID resourceId = UUID.randomUUID();

        // The aspect's own pool starts with no scope; a plain Thread reproduces that.
        runUnscoped(() -> auditLogAspect.saveAuditLog(
                "PROJECT_CREATE", "Project", resourceId, userId, orgA, "SUCCESS", null, 7, "127.0.0.1", null));

        TenantContext.runAsSystem(() -> {
            List<AuditLog> written = auditLogRepository.findAll().stream()
                    .filter(a -> resourceId.equals(a.getResourceId()))
                    .toList();
            assertThat(written)
                    .as("nothing was written — the writer thread had no tenant scope and the "
                            + "failure was swallowed by saveAuditLog's catch")
                    .hasSize(1);
            assertThat(written.get(0).getOrganizationId()).isEqualTo(orgA);
        });
    }

    @Test
    @DisplayName("an action with no organization — login, register, password reset — still writes the row")
    void auditLogWithoutAnOrganizationIsStillWritten() throws Exception {
        UUID resourceId = UUID.randomUUID();

        runUnscoped(() -> auditLogAspect.saveAuditLog(
                "LOGIN", "Auth", resourceId, userId, null, "SUCCESS", null, 3, "127.0.0.1", null));

        TenantContext.runAsSystem(() -> {
            List<AuditLog> written = auditLogRepository.findAll().stream()
                    .filter(a -> resourceId.equals(a.getResourceId()))
                    .toList();
            assertThat(written).hasSize(1);
            // The nil-UUID sentinel matches no organization; the point is that the row is written at all.
            assertThat(written.get(0).getOrganizationId()).isEqualTo(TenantContext.SYSTEM);
        });
    }

    @Test
    @DisplayName("entering a tenant scope inside an open transaction fails instead of mis-stamping")
    void tenantScopeInsideAnOpenTransactionIsRejected() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // The transaction is open, so Hibernate already resolved this session's tenant.
        assertThatThrownBy(() -> TenantContext.runAsSystem(() ->
                transaction.executeWithoutResult(status ->
                        TenantContext.runAs(orgA, () -> projectRepository.count()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the transaction");
    }

    @Test
    @DisplayName("a task on a hand-built pool writes under the submitting thread's organization")
    void wrappedPoolStampsTheSubmittersOrganization() throws Exception {
        ExecutorService pool = TenantPropagatingTaskDecorator.wrap(Executors.newSingleThreadExecutor());
        try {
            // organizationId unset on purpose: the worker thread must inherit orgA's scope.
            UUID projectId = TenantContext.callAs(orgA, () -> {
                try {
                    return pool.submit(() -> projectRepository.save(
                            Project.builder().name("pool-written").build()).getId()).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("the pooled write failed", e);
                }
            });

            TenantContext.runAs(orgA, () ->
                    assertThat(projectRepository.findById(projectId))
                            .as("written under orgA's scope, so orgA must see it")
                            .isPresent());
            TenantContext.runAs(orgB, () ->
                    assertThat(projectRepository.findById(projectId))
                            .as("a row stamped with the wrong organization would show up here")
                            .isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a user in two organizations sees both, from inside either one's scope")
    void getUserOrganizationsSpansEveryMembership() {
        // Under the system scope this would pass even with the bug present.
        List<OrganizationResponse> fromA = TenantContext.callAs(orgA, () ->
                organizationService.getUserOrganizations(userId));
        List<OrganizationResponse> fromB = TenantContext.callAs(orgB, () ->
                organizationService.getUserOrganizations(userId));

        assertThat(fromA).extracting(OrganizationResponse::getId).containsExactlyInAnyOrder(orgA, orgB);
        assertThat(fromB).extracting(OrganizationResponse::getId).containsExactlyInAnyOrder(orgA, orgB);
    }

    private static void runUnscoped(Runnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "unscoped-writer");
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new IllegalStateException("Unscoped body threw", failure.get());
        }
    }
}
