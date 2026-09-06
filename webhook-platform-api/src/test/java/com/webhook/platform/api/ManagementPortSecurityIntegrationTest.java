package com.webhook.platform.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Compose deployment splits actuator onto its own port so Prometheus can
 * scrape metrics without a JWT or an API key — that reasoning is written down in
 * three places: SecurityConfig's comment on the /actuator matchers,
 * monitoring/prometheus/prometheus.yml, and monitoring/README.md.
 *
 * <p>It was not true. Boot copies the parent context's servlet filters into the
 * management child context, so the main filter chain applied to the management
 * port as well and /actuator/prometheus answered 401 there — metrics scraping
 * has been quietly broken, with the comments asserting it worked.</p>
 *
 * <p>This pins both halves: open on the management port, closed on the main one.
 * The second half is the one that matters if this ever regresses in the other
 * direction — the management port is deliberately never published to the host,
 * and the main port very much is.</p>
 *
 * <p>Driven by {@link RestTestClient} rather than a {@code TestRestTemplate},
 * which Boot 4 removed. Same property that made the latter the right tool here:
 * it reports a 401 instead of throwing on one, which three of these four
 * assertions depend on.</p>
 */
@TestPropertySource(properties = "management.server.port=0")
public class ManagementPortSecurityIntegrationTest extends AbstractIntegrationTest {

    private final RestTestClient rest = RestTestClient.bindToServer().build();

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    public void metricsAreScrapableOnTheManagementPort() {
        rest.get().uri("http://localhost:" + managementPort + "/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .as("Prometheus scrapes this port with no credentials")
                        .contains("jvm_memory_used_bytes"));
    }

    @Test
    public void healthIsReachableOnTheManagementPort() {
        rest.get().uri("http://localhost:" + managementPort + "/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    public void metricsStayClosedOnTheMainPort() {
        rest.get().uri("http://localhost:" + serverPort + "/actuator/prometheus")
                .exchange()
                .expectStatus().value(status -> assertThat(status)
                        .as("the main port is the published one; metrics must not be anonymous there")
                        .isEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    @Test
    public void theManagementPortIsActuatorOnly() {
        // Opening the management port must not open the platform API on it.
        rest.get().uri("http://localhost:" + managementPort + "/api/v1/projects")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(HttpStatus.OK.value()));
    }
}
