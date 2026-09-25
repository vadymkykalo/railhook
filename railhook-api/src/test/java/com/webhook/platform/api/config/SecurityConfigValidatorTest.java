package com.webhook.platform.api.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityConfigValidatorTest {

    @ParameterizedTest
    @CsvSource({"production", "PRODUCTION", "Production"})
    void productionRefusesPrivateIps(String appEnv) {
        assertThrows(IllegalStateException.class, validator(appEnv, true)::validate);
    }

    @ParameterizedTest
    @CsvSource({"production, false", "development, true", "staging, true", "development, false"})
    void everythingElsePasses(String appEnv, boolean allowPrivateIps) {
        assertDoesNotThrow(validator(appEnv, allowPrivateIps)::validate);
    }

    private static SecurityConfigValidator validator(String appEnv, boolean allowPrivateIps) {
        SecurityConfigValidator validator = new SecurityConfigValidator();
        ReflectionTestUtils.setField(validator, "allowPrivateIps", allowPrivateIps);
        ReflectionTestUtils.setField(validator, "appEnv", appEnv);
        return validator;
    }
}
