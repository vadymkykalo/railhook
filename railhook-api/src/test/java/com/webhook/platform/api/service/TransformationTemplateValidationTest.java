package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.SubscriptionRepository;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.domain.repository.TransformationVersionRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.TransformationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A malformed JSONPath is caught where the person who can fix it is looking.
 *
 * <p>Validation checked that a template parsed as JSON and that each {@code ${...}} started with
 * a {@code $}. "{@code $} plus nonsense" passed both. It then failed at delivery time — once per
 * attempt, for every event, for as long as nobody noticed — and before the worker was taught to
 * fail loudly it did not even do that: {@code evaluateJsonPath} swallowed the error at DEBUG and
 * substituted a JSON null, so the receiver got a delivered, signed body with holes in it.
 *
 * <p>The author is the only person who can fix a typo in their own path, and saving the
 * transformation is the one moment they are looking at it.
 */
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
        /* The prefix check would have passed this and so must the compiler: rejecting real
           JSONPath to catch typos would be a worse trade than the bug being fixed. */
        assertThatCode(() -> service.create(projectId,
                request("{\"first\":\"${$.items[?(@.active == true)].name}\"}"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a template full of unclosed ${ openers is rejected in linear time")
    void unclosedExpressionOpenersDoNotBacktrack() {
        /* CodeQL java/polynomial-redos: `\$\{([^}]*)\}` rescans to the end of the template from
           every `${` when no `}` follows, so a valid-JSON template of repeated "${{" took
           quadratic time on save — a request anyone with write access could send. */
        String template = "{\"a\":\"" + "${{".repeat(60_000) + "\"}";
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            try {
                service.create(projectId, request(template), null);
            } catch (IllegalArgumentException expected) {
                // rejected or accepted is not the point; finishing promptly is
            }
        });
    }

    private TransformationRequest request(String template) {
        TransformationRequest r = new TransformationRequest();
        r.setName("t");
        r.setTemplate(template);
        return r;
    }

    /**
     * The engine is built lazily inside itself, so a test that only ever validates templates
     * never brings GraalJS up at all.
     */
    private static com.webhook.platform.common.transform.JavaScriptTransformEngine scriptEngine() {
        return new com.webhook.platform.common.transform.JavaScriptTransformEngine(
                new ObjectMapper(), com.webhook.platform.common.transform.ScriptLimits.defaults());
    }
}
