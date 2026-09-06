package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.DeliveryAttempt;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.DeliveryAttemptResponse;
import com.webhook.platform.api.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A PII masking rule has to hold on every path that shows a payload back.
 *
 * <p>{@code PiiMaskingService.sanitizePayload} was called from three places: the project events
 * list, the event diff, and a shared debug link. It was not called on the delivery detail view,
 * on a delivery's attempts — which carry up to 100 KB of request and response body each — on an
 * incoming event's raw body, or on the DLQ listing. Those are the screens an operator actually
 * opens when a delivery fails, which is exactly when the payload is in front of them.
 *
 * <p>So configuring a rule produced a dashboard that looked redacted in the places a user
 * checked and was not redacted in the places they debugged. A masking feature that holds
 * somewhere is worse than none, because it is trusted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DeliveryService — masking rules reach the attempt bodies and the replay preview")
class DeliveryPiiMaskingTest {

    private static final String RAW = "{\"email\":\"ada@example.com\"}";
    private static final String MASKED = "{\"email\":\"***\"}";

    @Mock private DeliveryRepository deliveryRepository;
    @Mock private DeliveryAttemptRepository deliveryAttemptRepository;
    @Mock private EndpointRepository endpointRepository;
    @Mock private EventRepository eventRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private DeliveryDispatch deliveryDispatch;
    @Mock private PiiMaskingService piiMaskingService;
    @Mock private AuthContext auth;

    private DeliveryService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID deliveryId = UUID.randomUUID();
    private final UUID endpointId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DeliveryService(deliveryRepository, deliveryAttemptRepository,
                endpointRepository, eventRepository, projectRepository, new ObjectMapper(),
                deliveryDispatch, piiMaskingService);

        Event event = Event.builder().id(eventId).projectId(projectId)
                .eventType("user.signup").payload(RAW).build();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId)
                .endpointId(endpointId).status(DeliveryStatus.FAILED)
                .attemptCount(1).maxAttempts(6).build();

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(Project.builder().id(projectId).name("p").build()));
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(
                Endpoint.builder().id(endpointId).projectId(projectId)
                        .url("https://api.customer.com/hook").enabled(true).build()));

        when(piiMaskingService.sanitizePayload(eq(projectId), anyString()))
                .thenAnswer(inv -> MASKED);
    }

    @Test
    @DisplayName("an attempt's request and response bodies are masked")
    void attemptBodiesAreMasked() {
        when(deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId))
                .thenReturn(List.of(DeliveryAttempt.builder()
                        .id(UUID.randomUUID()).deliveryId(deliveryId).attemptNumber(1)
                        .requestBody(RAW).responseBody(RAW).httpStatusCode(500).build()));

        List<DeliveryAttemptResponse> attempts = service.getDeliveryAttempts(deliveryId, auth);

        /* The request body is the payload as it was actually sent, and the response body is
           whatever the receiver echoed back — routinely the same personal data. Both are up to
           100 KB and both were returned raw. */
        assertThat(attempts).singleElement().satisfies(a -> {
            assertThat(a.getRequestBody()).isEqualTo(MASKED);
            assertThat(a.getResponseBody()).isEqualTo(MASKED);
        });
    }

    @Test
    @DisplayName("the replay dry-run preview is masked")
    void dryRunPayloadIsMasked() {
        when(deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId))
                .thenReturn(List.of());

        var response = service.dryRunReplay(deliveryId, auth);

        /* "What exactly would be re-sent" is a screen a support engineer opens while sharing
           their display. It showed the event payload verbatim. */
        assertThat(response.getPayload()).isEqualTo(MASKED);
    }

    @Test
    @DisplayName("a project with no rules is not charged for a rewrite it did not ask for")
    void noRulesLeavesPayloadUntouched() {
        when(piiMaskingService.sanitizePayload(eq(projectId), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId))
                .thenReturn(List.of(DeliveryAttempt.builder()
                        .id(UUID.randomUUID()).deliveryId(deliveryId).attemptNumber(1)
                        .requestBody(RAW).responseBody(null).build()));

        List<DeliveryAttemptResponse> attempts = service.getDeliveryAttempts(deliveryId, auth);

        assertThat(attempts).singleElement().satisfies(a -> {
            assertThat(a.getRequestBody()).isEqualTo(RAW);
            // A null body must stay null rather than becoming the string "null" on its way
            // through a sanitizer that was never asked to handle one.
            assertThat(a.getResponseBody()).isNull();
        });
    }
}
