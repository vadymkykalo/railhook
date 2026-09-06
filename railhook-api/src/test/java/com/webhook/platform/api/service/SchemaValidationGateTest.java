package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.SchemaValidationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchemaValidationGateTest {

    @Mock private PayloadSchemaValidator payloadSchemaValidator;

    private SchemaValidationGate gate;

    private final UUID projectId = UUID.randomUUID();
    private final Object payload = Map.of("id", 1);

    @BeforeEach
    void setUp() {
        gate = new SchemaValidationGate(payloadSchemaValidator, new ObjectMapper());
    }

    private Project project(SchemaValidationPolicy policy, boolean enabled) {
        Project p = new Project();
        p.setSchemaValidationEnabled(enabled);
        p.setSchemaValidationPolicy(policy);
        return p;
    }

    @Test
    void warnHandsTheErrorsBackSoTheCallerCanSeeThem() {
        when(payloadSchemaValidator.validate(eq(projectId), eq("order.created"), any()))
                .thenReturn(List.of("$.total: required field missing"));

        List<String> warnings = gate.check(project(SchemaValidationPolicy.WARN, true),
                projectId, "order.created", payload);

        assertThat(warnings).containsExactly("$.total: required field missing");
    }

    @Test
    void aPayloadThatMatchesWarnsAboutNothing() {
        when(payloadSchemaValidator.validate(eq(projectId), eq("order.created"), any()))
                .thenReturn(List.of());

        assertThat(gate.check(project(SchemaValidationPolicy.WARN, true),
                projectId, "order.created", payload)).isEmpty();
    }

    @Test
    void blockStillRefusesTheEvent() {
        when(payloadSchemaValidator.validate(eq(projectId), eq("order.created"), any()))
                .thenReturn(List.of("$.total: required field missing"));

        assertThatThrownBy(() -> gate.check(project(SchemaValidationPolicy.BLOCK, true),
                projectId, "order.created", payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("$.total");
    }

    @Test
    void validationOffSkipsTheValidatorEntirely() {
        assertThat(gate.check(project(SchemaValidationPolicy.WARN, false),
                projectId, "order.created", payload)).isEmpty();

        verify(payloadSchemaValidator, never()).validate(any(), any(), any());
        verify(payloadSchemaValidator, never()).autoDiscover(any(), any(), any());
    }

    @Test
    void noProjectSkipsTheValidatorEntirely() {
        assertThat(gate.check(null, projectId, "order.created", payload)).isEmpty();

        verify(payloadSchemaValidator, never()).validate(any(), any(), any());
    }
}
