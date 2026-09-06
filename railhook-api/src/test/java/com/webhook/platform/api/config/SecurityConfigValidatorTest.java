package com.webhook.platform.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class SecurityConfigValidatorTest {

    @Test
    void testProductionWithAllowPrivateIpsThrowsException() {
        // Given: production environment with allow-private-ips enabled
        SecurityConfigValidator validator = new SecurityConfigValidator();
        ReflectionTestUtils.setField(validator, "allowPrivateIps", true);
        ReflectionTestUtils.setField(validator, "appEnv", "production");

        // When/Then: validate() should throw IllegalStateException
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                validator::validate);
        
        assertTrue(exception.getMessage().contains("FATAL SECURITY ERROR"));
        assertTrue(exception.getMessage().contains("allow-private-ips=true is forbidden"));
        assertTrue(exception.getMessage().contains("SSRF protection is disabled"));
    }

    @Test
    void testProductionWithAllowPrivateIpsFalsePasses() {
        // Given: production environment with allow-private-ips disabled (secure)
        SecurityConfigValidator validator = new SecurityConfigValidator();
        ReflectionTestUtils.setField(validator, "allowPrivateIps", false);
        ReflectionTestUtils.setField(validator, "appEnv", "production");

        // When/Then: validate() should NOT throw
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void testDevelopmentWithAllowPrivateIpsPasses() {
        // Given: development environment with allow-private-ips enabled
        SecurityConfigValidator validator = new SecurityConfigValidator();
        ReflectionTestUtils.setField(validator, "allowPrivateIps", true);
        ReflectionTestUtils.setField(validator, "appEnv", "development");

        // When/Then: validate() should NOT throw (acceptable in dev)
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void testStagingWithAllowPrivateIpsThrowsException() {
        // Given: staging environment with allow-private-ips enabled
        SecurityConfigValidator validator = new SecurityConfigValidator();
        ReflectionTestUtils.setField(validator, "allowPrivateIps", true);
        ReflectionTestUtils.setField(validator, "appEnv", "staging");

        // When/Then: validate() should NOT throw (only production is strict)
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void testProductionCaseInsensitive() {
        // Given: "PRODUCTION" in uppercase
        SecurityConfigValidator validator = new SecurityConfigValidator();
        ReflectionTestUtils.setField(validator, "allowPrivateIps", true);
        ReflectionTestUtils.setField(validator, "appEnv", "PRODUCTION");

        // When/Then: should still throw (case-insensitive check)
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                validator::validate);
        
        assertTrue(exception.getMessage().contains("FATAL SECURITY ERROR"));
    }

    @Test
    void testProductionMixedCase() {
        // Given: "Production" in mixed case
        SecurityConfigValidator validator = new SecurityConfigValidator();
        ReflectionTestUtils.setField(validator, "allowPrivateIps", true);
        ReflectionTestUtils.setField(validator, "appEnv", "Production");

        // When/Then: should still throw (case-insensitive check)
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void testDefaultValuesPass() {
        // Given: default values (false, development)
        SecurityConfigValidator validator = new SecurityConfigValidator();
        ReflectionTestUtils.setField(validator, "allowPrivateIps", false);
        ReflectionTestUtils.setField(validator, "appEnv", "development");

        // When/Then: validate() should pass
        assertDoesNotThrow(validator::validate);
    }
}
