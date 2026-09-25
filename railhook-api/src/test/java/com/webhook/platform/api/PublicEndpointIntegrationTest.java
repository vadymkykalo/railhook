package com.webhook.platform.api;

import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.demo.DemoTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Undoes AbstractIntegrationTest's system scope: a real anonymous request has none.
@AutoConfigureMockMvc
public class PublicEndpointIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void becomeAnonymous() {
        TenantContext.clear();
    }

    @Test
    public void thePlanCatalogAnswersWithoutCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/billing/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").exists());
    }

    @Test
    public void thePlanCatalogDoesNotOfferTheSelfHostedRow() throws Exception {
        mockMvc.perform(get("/api/v1/billing/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'self_hosted')]").isEmpty());
    }

    @Test
    public void theDemoIsNotThereUnlessTheOperatorTurnsItOn() throws Exception {
        // Off by default: a self-hosted install answers as if the path did not exist.
        mockMvc.perform(post("/api/v1/public/demo/session")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations WHERE id = ?",
                Integer.class, DemoTenant.ORGANIZATION_ID)).isZero();
    }
}
