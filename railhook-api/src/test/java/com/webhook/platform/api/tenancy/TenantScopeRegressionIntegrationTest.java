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

/**
 * Pins the three tenancy failures that produced no error anybody would see.
 *
 * <p>{@code CrossTenantIsolationTest} proves the filter confines rows. These are the other
 * direction: work that is <em>supposed</em> to reach rows, and stopped. Each one failed silently —
 * two into a {@code catch (Exception)} that logged the wrong cause, and one by returning a
 * plausible-looking answer with rows missing from it — which is why none of them had a test and
 * why the build stayed green.
 *
 * <ul>
 *   <li><b>Workflow executions.</b> The outbox poller is {@code @SystemTenant}, and under
 *       Hibernate's root tenant nothing is stamped on insert. {@code workflow_executions
 *       .organization_id} went NOT NULL in V056, so every trigger became a constraint violation
 *       caught as "concurrent duplicate".</li>
 *   <li><b>Audit log.</b> Written on a pool this codebase builds by hand, so
 *       {@code TenantPropagatingTaskDecorator} never wraps it and the writer thread has no scope
 *       at all — every audited action, login and CRUD alike, wrote nothing.</li>
 *   <li><b>Organization list.</b> {@code Membership} took {@code @TenantId}, so the "every
 *       organization you belong to" read was filtered to the one the current token names.</li>
 * </ul>
 *
 * <p>Each of the three is now also held by a guard, and this class carries a case per guard
 * rather than a case per endpoint: the transaction guard in {@link TenantContext#callAs} and the
 * propagation {@link TenantPropagatingTaskDecorator#wrap} gives a hand-built pool, both exercised
 * here against a real transaction manager and a real session rather than a set flag. The third
 * guard — {@code NativeQueryTenantPredicateTest} — has no runtime counterpart on purpose: it
 * asserts on the SQL string, because a runtime check would only ever cover the queries some test
 * happens to call.
 *
 * <p>Named {@code *IntegrationTest} so it routes to the Docker-backed job — see
 * {@code scripts/check-test-routing.sh}.
 */
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

    // ── 1. Workflow executions ──────────────────────────────────────

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

        // Exactly how WorkflowTriggerOutboxService.poll calls it: no ambient organization, only
        // the system scope. Nothing here tells the trigger service whose row to write except the
        // workflow it just read.
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

        // A row stamped with the sentinel would be invisible here even though it exists, which is
        // what "the dashboard shows no runs" would have looked like.
        TenantContext.runAs(orgA, () ->
                assertThat(workflowExecutionRepository.findAll())
                        .extracting(WorkflowExecution::getWorkflowId)
                        .contains(workflowId));
    }

    // ── 2. Audit log ────────────────────────────────────────────────

    @Test
    @DisplayName("the audit writer thread, which starts with no tenant scope, still writes the row")
    void auditLogIsWrittenFromAnUnscopedThread() throws Exception {
        UUID resourceId = UUID.randomUUID();

        // The aspect hands saveAuditLog to a single-thread pool it builds itself, so the writer
        // thread never sees TenantPropagatingTaskDecorator and starts with nothing. A plain
        // Thread reproduces that condition without reaching into the aspect's private executor.
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
            // The point of this case is that the row is written at all. The sentinel is now
            // chosen in the aspect rather than left to Hibernate's @TenantId generator, but the
            // value is the same one it used to fill in: the nil UUID, which matches no
            // organization, so a tenant-scoped reader sees this row no more than it saw the
            // the older SQL NULL. Pinned because it is a real difference from that NULL.
            assertThat(written.get(0).getOrganizationId()).isEqualTo(TenantContext.SYSTEM);
        });
    }

    // ── 2b. The guards that turned each of these into a build failure ───

    @Test
    @DisplayName("entering a tenant scope inside an open transaction fails instead of mis-stamping")
    void tenantScopeInsideAnOpenTransactionIsRejected() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // The shape the guard exists for: the transaction is already open, so Hibernate has
        // already resolved the tenant for this session and the scope change cannot reach it.
        // Before the guard this wrote a row belonging to whoever was in scope when the
        // transaction began, and said nothing.
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
            // organizationId is left unset on purpose: the whole claim is that the worker thread
            // inherited orgA's scope and Hibernate stamped the row from it.
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

    // ── 3. Organization list ────────────────────────────────────────

    @Test
    @DisplayName("a user in two organizations sees both, from inside either one's scope")
    void getUserOrganizationsSpansEveryMembership() {
        // The scope matters: this is a request from a token that names orgA, and the answer must
        // not be filtered to it. Running the same call under the system scope would pass even
        // with the bug present.
        List<OrganizationResponse> fromA = TenantContext.callAs(orgA, () ->
                organizationService.getUserOrganizations(userId));
        List<OrganizationResponse> fromB = TenantContext.callAs(orgB, () ->
                organizationService.getUserOrganizations(userId));

        assertThat(fromA).extracting(OrganizationResponse::getId).containsExactlyInAnyOrder(orgA, orgB);
        assertThat(fromB).extracting(OrganizationResponse::getId).containsExactlyInAnyOrder(orgA, orgB);
    }

    /** Runs {@code body} on a fresh thread with no tenant scope, rethrowing whatever it threw. */
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
