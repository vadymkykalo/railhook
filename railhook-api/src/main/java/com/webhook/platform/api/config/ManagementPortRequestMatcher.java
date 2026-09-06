package com.webhook.platform.api.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Matches requests that arrived on the actuator's own port, when there is one.
 *
 * <p>Setting {@code management.server.port} puts actuator on a separate port and
 * a separate child application context — but Boot copies the parent context's
 * servlet filters into that child, so the main security chain applies there too.
 * Which meant {@code /actuator/prometheus} answered 401 on the very port that
 * exists so Prometheus can scrape it without credentials. This matcher is how
 * the chain tells the two ports apart.</p>
 *
 * <p>It reads the ports Boot resolved at startup rather than the configured
 * values, because {@code port=0} is a real and common setting (every test using
 * a random port) and the configured value then says nothing about what is
 * actually listening.</p>
 *
 * <p>When the ports are not split — the default, and what a plain
 * {@code spring-boot:run} does — nothing matches, and actuator stays behind the
 * authenticated chain exactly as before. That is the important half: the main
 * port is the one that gets published.</p>
 */
public class ManagementPortRequestMatcher implements RequestMatcher {

    private final Environment environment;

    public ManagementPortRequestMatcher(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        Integer managementPort = environment.getProperty("local.management.port", Integer.class);
        Integer serverPort = environment.getProperty("local.server.port", Integer.class);

        if (managementPort == null || serverPort == null || managementPort.equals(serverPort)) {
            return false;
        }
        return request.getLocalPort() == managementPort;
    }
}
