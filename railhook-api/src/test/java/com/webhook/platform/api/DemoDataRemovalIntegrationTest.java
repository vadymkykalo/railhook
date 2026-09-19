package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.api.service.demo.DemoDataRemover;
import com.webhook.platform.api.service.demo.DemoDataSeeder;
import com.webhook.platform.common.demo.DemoTenant;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Turning the demo off takes its rows with it: an installation that ran the demo once and then
 * disabled it is left with nothing of the demo's, and nothing of anyone else's is touched.
 *
 * <p>This context has the demo off, as a restart after {@code DEMO_ENABLED=false} would. The
 * demo's rows are put in place by a seeder built by hand, which is what the earlier boot with the
 * demo on left behind.
 */
class DemoDataRemovalIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> DEMO_TABLES = List.of(
            "workflow_step_executions", "workflow_executions", "workflows",
            "incoming_forward_attempts", "incoming_events", "incoming_destinations", "incoming_sources",
            "delivery_attempts", "deliveries", "events", "subscriptions", "endpoints", "projects", "memberships");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EncryptionKeyRegistry encryptionKeyRegistry;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DemoDataRemover remover;

    @Test
    void withTheDemoOffThereIsNoSeederOnlyTheRemover() {
        assertThat(context.getBeansOfType(DemoDataSeeder.class)).isEmpty();
        assertThat(context.getBeansOfType(DemoDataRemover.class)).hasSize(1);
    }

    @Test
    void removesEverythingTheDemoLeftAndNothingElse() throws Exception {
        UUID neighbourProject = registerNeighbourWithAnEvent();
        UUID neighbourOrganization = jdbc.queryForObject("SELECT organization_id FROM projects WHERE id = ?",
                UUID.class, neighbourProject);
        int neighbourRowsBefore = rowsOf(neighbourOrganization);

        new DemoDataSeeder(jdbc, transactionManager, encryptionKeyRegistry, Clock.systemUTC(), false).seed();
        assertThat(rowsOf(DemoTenant.ORGANIZATION_ID)).isPositive();

        DemoDataRemover.Removed removed = remover.removeIfPresent();

        assertThat(removed.organization()).isTrue();
        assertThat(removed.user()).isTrue();
        assertThat(removed.events()).isPositive();
        assertThat(removed.deliveries()).isPositive();
        assertThat(removed.incomingEvents()).isPositive();
        assertThat(removed.workflowExecutions()).isPositive();
        assertThat(rowsOf(DemoTenant.ORGANIZATION_ID)).isZero();
        assertThat(count("SELECT COUNT(*) FROM organizations WHERE id = ?", DemoTenant.ORGANIZATION_ID)).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ?", DemoTenant.USER_ID)).isZero();
        assertThat(count("SELECT COUNT(*) FROM projects WHERE id = ?", DemoTenant.PROJECT_ID)).isZero();

        assertThat(rowsOf(neighbourOrganization)).isEqualTo(neighbourRowsBefore);
        assertThat(count("SELECT COUNT(*) FROM organizations WHERE id = ?", neighbourOrganization)).isOne();
        assertThat(count("SELECT COUNT(*) FROM users WHERE email = ?", "demo-removal-neighbour@example.com")).isOne();
    }

    @Test
    void aSecondRunFindsNothingAndChangesNothing() throws Exception {
        UUID neighbourProject = registerNeighbourWithAnEvent();
        UUID neighbourOrganization = jdbc.queryForObject("SELECT organization_id FROM projects WHERE id = ?",
                UUID.class, neighbourProject);
        new DemoDataSeeder(jdbc, transactionManager, encryptionKeyRegistry, Clock.systemUTC(), false).seed();
        remover.removeIfPresent();
        int neighbourRows = rowsOf(neighbourOrganization);

        DemoDataRemover.Removed again = remover.removeIfPresent();

        assertThat(again.anything()).isFalse();
        assertThat(rowsOf(neighbourOrganization)).isEqualTo(neighbourRows);
    }

    @Test
    void theDemoPersonIsKeptIfSomeoneMadeThemAMemberElsewhere() throws Exception {
        UUID neighbourProject = registerNeighbourWithAnEvent();
        UUID neighbourOrganization = jdbc.queryForObject("SELECT organization_id FROM projects WHERE id = ?",
                UUID.class, neighbourProject);
        new DemoDataSeeder(jdbc, transactionManager, encryptionKeyRegistry, Clock.systemUTC(), false).seed();
        jdbc.update("INSERT INTO memberships (id, user_id, organization_id, role, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VIEWER', 'ACTIVE', now(), now())",
                UUID.randomUUID(), DemoTenant.USER_ID, neighbourOrganization);

        try {
            DemoDataRemover.Removed removed = remover.removeIfPresent();

            assertThat(removed.organization()).isTrue();
            assertThat(removed.user()).isFalse();
            assertThat(count("SELECT COUNT(*) FROM users WHERE id = ?", DemoTenant.USER_ID)).isOne();
            assertThat(count("SELECT COUNT(*) FROM memberships WHERE user_id = ? AND organization_id = ?",
                    DemoTenant.USER_ID, neighbourOrganization)).isOne();
        } finally {
            // The other tests share this database and start from a demo person that is the demo's alone.
            jdbc.update("DELETE FROM memberships WHERE user_id = ?", DemoTenant.USER_ID);
            jdbc.update("DELETE FROM users WHERE id = ?", DemoTenant.USER_ID);
        }
    }

    private UUID registerNeighbourWithAnEvent() throws Exception {
        Integer existing = count("SELECT COUNT(*) FROM users WHERE email = ?", "demo-removal-neighbour@example.com");
        String owner;
        if (existing == 0) {
            MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                    .email("demo-removal-neighbour@example.com").password("Test1234!")
                                    .organizationName("Neighbour").build())))
                    .andExpect(status().isCreated())
                    .andReturn();
            owner = objectMapper.readValue(registered.getResponse().getContentAsString(), AuthResponse.class)
                    .getAccessToken();
        } else {
            return jdbc.queryForObject("SELECT p.id FROM projects p JOIN memberships m ON m.organization_id = "
                    + "p.organization_id JOIN users u ON u.id = m.user_id WHERE u.email = ? LIMIT 1",
                    UUID.class, "demo-removal-neighbour@example.com");
        }
        MvcResult created = mockMvc.perform(post("/api/v1/projects").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Neighbour's own\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID project = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText());
        UUID organization = jdbc.queryForObject("SELECT organization_id FROM projects WHERE id = ?", UUID.class, project);
        jdbc.update("INSERT INTO events (id, organization_id, project_id, event_type, payload, payload_compressed, "
                        + "created_at) VALUES (?, ?, ?, 'order.created', '{}'::jsonb, false, now())",
                UUID.randomUUID(), organization, project);
        return project;
    }

    private int rowsOf(UUID organizationId) {
        int total = 0;
        for (String table : DEMO_TABLES) {
            total += count("SELECT COUNT(*) FROM " + table + " WHERE organization_id = ?", organizationId);
        }
        return total;
    }

    private Integer count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
