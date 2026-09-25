package com.webhook.platform.api.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// /actuator/health is anonymous and proxied to the public port, so show-details is public exposure.
class ActuatorHealthExposureTest {

    @Test
    void healthDetailsAreNotPublicButTheProbesStayEnabled() {
        Map<String, Object> health = healthEndpointConfig();

        assertEquals("when-authorized", health.get("show-details"));
        @SuppressWarnings("unchecked")
        Map<String, Object> probes = (Map<String, Object>) health.get("probes");
        assertNotNull(probes);
        assertEquals(true, probes.get("enabled"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> healthEndpointConfig() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(in, "application.yml not found on the test classpath");
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> management = (Map<String, Object>) root.get("management");
            Map<String, Object> endpoint = (Map<String, Object>) management.get("endpoint");
            return (Map<String, Object>) endpoint.get("health");
        } catch (Exception e) {
            throw new IllegalStateException("could not read application.yml", e);
        }
    }
}
