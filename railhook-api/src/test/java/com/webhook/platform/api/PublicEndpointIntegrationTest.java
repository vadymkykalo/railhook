package com.webhook.platform.api;

import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoints SecurityConfig marks {@code permitAll}, asked the way an anonymous caller asks.
 *
 * <p>{@code TenantContextFilter} sets a scope from the caller's JWT or API key and leaves it
 * unset otherwise, and {@code OrganizationTenantResolver} refuses to guess — so any permitted
 * endpoint that reaches the database answers 500 with {@code TenantNotResolvedException}. The
 * plan catalog is one: a controller comment calls it public, SecurityConfig permits it, and it
 * has never worked without a token. Nothing noticed because the only caller is the dashboard's
 * billing page, which is behind a login.
 *
 * <p>This test has to undo what {@link AbstractIntegrationTest} does for every other test.
 * That base class enters the system scope in a {@code @BeforeEach} so integration tests can seed
 * fixtures across organizations, and a subclass {@code @BeforeEach} runs after it — which is
 * exactly why the bug survived: any test written the ordinary way carries a scope the real
 * anonymous request does not have, and passes.
 */
@AutoConfigureMockMvc
public class PublicEndpointIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
        // Not a plan anyone is sold; it exists so a self-hosted deployment has an unlimited
        // row to point at. Asserted here rather than in a service test because this is the
        // response an unauthenticated visitor sees.
        mockMvc.perform(get("/api/v1/billing/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'self_hosted')]").isEmpty());
    }
}
