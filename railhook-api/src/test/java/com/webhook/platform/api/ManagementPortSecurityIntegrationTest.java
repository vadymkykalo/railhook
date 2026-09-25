package com.webhook.platform.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

// Boot copies the main filter chain onto the management port; pins open there, closed on main.
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
        rest.get().uri("http://localhost:" + managementPort + "/api/v1/projects")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(HttpStatus.OK.value()));
    }
}
