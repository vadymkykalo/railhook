package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.SubscriptionRepository;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.domain.repository.TransformationVersionRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.TransformationRequest;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.common.transform.JavaScriptTransformEngine;
import com.webhook.platform.common.transform.ScriptLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// A bad JSONPath used to fail at delivery time, once per attempt, instead of on save.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TransformationService — a template's JSONPaths are compiled, not just prefix-checked")
class TransformationTemplateValidationTest {

    @Mock private TransformationRepository transformationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private IncomingDestinationRepository incomingDestinationRepository;
    @Mock private TransformationVersionRepository transformationVersionRepository;
    @Mock private UserRepository userRepository;

    private TransformationService service;

    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new TransformationService(transformationRepository, transformationVersionRepository,
                projectRepository, subscriptionRepository, incomingDestinationRepository,
                userRepository, new JsonDiffCalculator(objectMapper), objectMapper,
                scriptEngine(), 50);

        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(Project.builder().id(projectId).name("p").build()));
        when(transformationRepository.existsByProjectIdAndName(any(), any())).thenReturn(false);
        when(transformationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("a path that starts with $ but does not parse is rejected on save")
    void malformedPathRejectedAtSaveTime() {
        assertThatThrownBy(() -> service.create(projectId, request("{\"id\":\"${$.[[nope}\"}"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid JSONPath");
    }

    @Test
    @DisplayName("an ordinary template still saves")
    void validTemplateIsAccepted() {
        assertThatCode(() -> service.create(projectId,
                request("{\"id\":\"${$.id}\",\"email\":\"${$.data.customer.email}\"}"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a filter expression is a valid path, not a syntax error")
    void filterExpressionIsAccepted() {
        assertThatCode(() -> service.create(projectId,
                request("{\"first\":\"${$.items[?(@.active == true)].name}\"}"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a template full of unclosed ${ openers is rejected in linear time")
    void unclosedExpressionOpenersDoNotBacktrack() {
        // The old regex rescanned to the end from every '${', quadratic on save.
        String template = "{\"a\":\"" + "${{".repeat(60_000) + "\"}";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try {
                service.create(projectId, request(template), null);
            } catch (IllegalArgumentException expected) {
            }
        });
    }

    @Test
    void deletingATransformationStillInUseIsAConflict() {
        UUID id = UUID.randomUUID();
        when(transformationRepository.findByIdAndProjectId(id, projectId))
                .thenReturn(Optional.of(Transformation.builder().id(id).projectId(projectId).build()));
        when(subscriptionRepository.countByTransformationId(id)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(projectId, id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("referenced by 2 subscriptions");
    }

    private TransformationRequest request(String template) {
        TransformationRequest r = new TransformationRequest();
        r.setName("t");
        r.setTemplate(template);
        return r;
    }

    private static JavaScriptTransformEngine scriptEngine() {
        return new JavaScriptTransformEngine(new ObjectMapper(), ScriptLimits.defaults());
    }
}
