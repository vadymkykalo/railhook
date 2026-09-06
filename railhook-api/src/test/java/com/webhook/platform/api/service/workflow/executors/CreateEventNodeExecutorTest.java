package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus;
import com.webhook.platform.api.dto.EventIngestRequest;
import com.webhook.platform.api.dto.EventIngestResponse;
import com.webhook.platform.api.service.EventIngestService;
import com.webhook.platform.api.service.workflow.StepResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateEventNodeExecutorTest {

    @Mock
    private EventIngestService eventIngestService;

    private final ObjectMapper mapper = new ObjectMapper();
    private CreateEventNodeExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CreateEventNodeExecutor(eventIngestService, mapper);
    }

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void getType_returnsCreateEvent() {
        assertThat(executor.getType()).isEqualTo("createEvent");
    }

    @Test
    void missingProjectId_returnsFailed() throws Exception {
        StepResult result = executor.execute(json("{\"eventType\":\"order.created\"}"), json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("projectId is required");
        verifyNoInteractions(eventIngestService);
    }

    @Test
    void invalidProjectIdFormat_returnsFailed() throws Exception {
        JsonNode config = json("{\"projectId\":\"not-a-uuid\",\"eventType\":\"order.created\"}");

        StepResult result = executor.execute(config, json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("invalid projectId format");
    }

    @Test
    void missingEventType_returnsFailed() throws Exception {
        UUID projectId = UUID.randomUUID();
        JsonNode config = json("{\"projectId\":\"" + projectId + "\"}");

        StepResult result = executor.execute(config, json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("eventType is required");
        verifyNoInteractions(eventIngestService);
    }

    @Test
    void noPayloadTemplate_forwardsWorkflowInputAsEventData() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        JsonNode config = json("{\"projectId\":\"" + projectId + "\",\"eventType\":\"order.created\"}");
        JsonNode input = json("{\"orderId\":\"o-1\"}");

        when(eventIngestService.ingestEvent(eq(projectId), any(EventIngestRequest.class), isNull()))
                .thenReturn(EventIngestResponse.builder().eventId(eventId).type("order.created").deliveriesCreated(3).build());

        StepResult result = executor.execute(config, input);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output().get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(result.output().get("deliveriesCreated").asInt()).isEqualTo(3);
        assertThat(result.output().get("projectId").asText()).isEqualTo(projectId.toString());

        ArgumentCaptor<EventIngestRequest> captor = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(eventIngestService).ingestEvent(eq(projectId), captor.capture(), isNull());
        assertThat(captor.getValue().getType()).isEqualTo("order.created");
        assertThat(captor.getValue().getData()).isEqualTo(input);
    }

    @Test
    void objectPayloadTemplate_usedAsEventData() throws Exception {
        UUID projectId = UUID.randomUUID();
        JsonNode template = json("{\"custom\":true}");
        var config = mapper.createObjectNode();
        config.put("projectId", projectId.toString());
        config.put("eventType", "custom.event");
        config.set("payloadTemplate", template);

        when(eventIngestService.ingestEvent(eq(projectId), any(EventIngestRequest.class), isNull()))
                .thenReturn(EventIngestResponse.builder().eventId(UUID.randomUUID()).deliveriesCreated(0).build());

        executor.execute(config, json("{\"ignored\":true}"));

        ArgumentCaptor<EventIngestRequest> captor = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(eventIngestService).ingestEvent(eq(projectId), captor.capture(), isNull());
        assertThat(captor.getValue().getData()).isEqualTo(template);
    }

    @Test
    void stringPayloadTemplate_isParsedAsJson() throws Exception {
        UUID projectId = UUID.randomUUID();
        JsonNode config = json("{\"projectId\":\"" + projectId + "\",\"eventType\":\"custom.event\",\"payloadTemplate\":\"{\\\"a\\\":1}\"}");

        when(eventIngestService.ingestEvent(eq(projectId), any(EventIngestRequest.class), isNull()))
                .thenReturn(EventIngestResponse.builder().eventId(UUID.randomUUID()).deliveriesCreated(0).build());

        executor.execute(config, json("{}"));

        ArgumentCaptor<EventIngestRequest> captor = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(eventIngestService).ingestEvent(eq(projectId), captor.capture(), isNull());
        assertThat(captor.getValue().getData().get("a").asInt()).isEqualTo(1);
    }

    @Test
    void malformedStringPayloadTemplate_fallsBackToWorkflowInput() throws Exception {
        UUID projectId = UUID.randomUUID();
        JsonNode config = json("{\"projectId\":\"" + projectId + "\",\"eventType\":\"custom.event\",\"payloadTemplate\":\"not json\"}");
        JsonNode input = json("{\"fallback\":true}");

        when(eventIngestService.ingestEvent(eq(projectId), any(EventIngestRequest.class), isNull()))
                .thenReturn(EventIngestResponse.builder().eventId(UUID.randomUUID()).deliveriesCreated(0).build());

        executor.execute(config, input);

        ArgumentCaptor<EventIngestRequest> captor = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(eventIngestService).ingestEvent(eq(projectId), captor.capture(), isNull());
        assertThat(captor.getValue().getData()).isEqualTo(input);
    }

    @Test
    void ingestServiceThrows_returnsFailed() throws Exception {
        UUID projectId = UUID.randomUUID();
        JsonNode config = json("{\"projectId\":\"" + projectId + "\",\"eventType\":\"order.created\"}");

        when(eventIngestService.ingestEvent(eq(projectId), any(EventIngestRequest.class), isNull()))
                .thenThrow(new RuntimeException("quota exceeded"));

        StepResult result = executor.execute(config, json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("Create Event error");
    }
}
