package com.webhook.platform.api.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The aggregate {@code /actuator/health} is anonymous and publicly routed.
 *
 * <p>{@code SecurityConfig} permits {@code /actuator/health} and {@code /actuator/health/**}
 * without authentication, and the UI's nginx proxies {@code /actuator/health} from the one
 * published port straight to the API's management port — deliberately, so that health stays
 * reachable without going through the JWT/API-key chain. That makes {@code show-details}
 * a public-exposure setting, not an operator convenience: {@code always} published component
 * names, the database product and version, and Redis/Kafka reachability to anyone who asked.</p>
 *
 * <p>Nothing that consumes health here needs the details — the Compose healthchecks, the Helm
 * probes, {@code make health} and install.sh's wait loop all read the liveness/readiness
 * probe groups, whose status is returned either way — so this asserts the setting does not
 * drift back.</p>
 */
class ActuatorHealthExposureTest {

    @ParameterizedTest
    @ValueSource(strings = {"application.yml"})
    void healthDetailsAreNotPublic(String resource) {
        assertEquals("when-authorized", showDetails(resource),
                "/actuator/health is anonymous and proxied to the public port: "
                        + "'always' hands its component detail to unauthenticated callers");
    }

    @Test
    void probesStayEnabledSoTheDeploymentsKeepWorking() {
        Map<String, Object> health = healthEndpointConfig("application.yml");
        @SuppressWarnings("unchecked")
        Map<String, Object> probes = (Map<String, Object>) health.get("probes");
        assertNotNull(probes, "liveness/readiness groups are what every healthcheck reads");
        assertEquals(true, probes.get("enabled"));
    }

    private String showDetails(String resource) {
        return (String) healthEndpointConfig(resource).get("show-details");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> healthEndpointConfig(String resource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " not found on the test classpath");
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> management = (Map<String, Object>) root.get("management");
            Map<String, Object> endpoint = (Map<String, Object>) management.get("endpoint");
            return (Map<String, Object>) endpoint.get("health");
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + resource, e);
        }
    }
}
