package com.webhook.platform.api.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validates security-critical configuration at application startup.
 * Prevents production deployment with unsafe settings.
 */
@Slf4j
@Component
public class SecurityConfigValidator {

    @Value("${webhook.url-validation.allow-private-ips:false}")
    private boolean allowPrivateIps;

    @Value("${app.env:development}")
    private String appEnv;

    @PostConstruct
    public void validate() {
        if (allowPrivateIps && "production".equalsIgnoreCase(appEnv)) {
            throw new IllegalStateException(
                "FATAL SECURITY ERROR: allow-private-ips=true is forbidden in production environment.\n" +
                "SSRF protection is disabled, which allows webhooks to be sent to private IPs (localhost, internal networks).\n" +
                "This is a critical security vulnerability.\n" +
                "Fix: Set WEBHOOK_ALLOW_PRIVATE_IPS=false or remove the override.\n" +
                "Current config: app.env=" + appEnv + ", allow-private-ips=" + allowPrivateIps
            );
        }

        if (allowPrivateIps) {
            log.warn("  SECURITY WARNING: SSRF protection is DISABLED (allow-private-ips=true).");
            log.warn("  Webhooks can be sent to private IPs (localhost, 10.0.0.0/8, 192.168.0.0/16, etc.).");
            log.warn("  This is acceptable for development/testing ONLY.");
            log.warn("  Current environment: {}", appEnv);
        } else {
            log.info(" SSRF protection enabled (allow-private-ips=false). Private IPs are blocked.");
        }
    }
}
