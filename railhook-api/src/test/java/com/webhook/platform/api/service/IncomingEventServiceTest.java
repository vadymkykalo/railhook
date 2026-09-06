package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.entity.IncomingEvent;
import com.webhook.platform.api.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.IncomingEventResponse;
import com.webhook.platform.api.dto.IncomingForwardAttemptResponse;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.service.ForwardDispatch;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncomingEventServiceTest {

    @Mock
    private IncomingEventRepository eventRepository;
    @Mock
    private IncomingSourceRepository sourceRepository;
    @Mock
    private IncomingForwardAttemptRepository forwardAttemptRepository;
    @Mock
    private IncomingDestinationRepository destinationRepository;
    @Mock
    private OutboxMessageRepository outboxMessageRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private PlatformTransactionManager txManager;

    private IncomingEventService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID destId = UUID.randomUUID();
    private final AuthContext auth = new AuthContext(UUID.randomUUID(), orgId, MembershipRole.OWNER, null, null);

    private Project project;
    @Mock private PiiMaskingService piiMaskingService;

    private IncomingSource source;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        // What the real service does for a project with no masking rules: hand the payload
        // straight back. An unstubbed mock returns null, which would silently assert that
        // masking blanks every body.
        lenient().when(piiMaskingService.sanitizePayload(any(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        service = new IncomingEventService(
                eventRepository, sourceRepository, forwardAttemptRepository,
                destinationRepository, outboxMessageRepository, projectRepository,
                objectMapper, new ForwardDispatch(objectMapper), txManager, piiMaskingService
        );
        project = Project.builder().id(projectId).organizationId(orgId).name("Test").build();
        source = IncomingSource.builder()
                .id(sourceId).projectId(projectId).name("Test Source")
                .slug("test").providerType(ProviderType.GENERIC)
                .status(IncomingSourceStatus.ACTIVE)
                .ingressPathToken("tok").verificationMode(VerificationMode.NONE)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }

    private IncomingEvent buildEvent() {
        return IncomingEvent.builder()
                .id(eventId).incomingSourceId(sourceId)
                .requestId("req-123").method("POST").path("/ingress/tok")
                .bodyRaw("{\"data\":1}").contentType("application/json")
                .clientIp("127.0.0.1").receivedAt(Instant.now())
                .build();
    }

    private void stubAccess() {
        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
    }

    @Test
    void listEvents_byProject() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        IncomingEvent event = buildEvent();
        when(eventRepository.findByProjectId(eq(projectId), any()))
                .thenReturn(new PageImpl<>(List.of(event)));
        when(sourceRepository.findAllById(List.of(sourceId))).thenReturn(List.of(source));

        Page<IncomingEventResponse> page = service.listEvents(projectId, null, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getRequestId()).isEqualTo("req-123");
        assertThat(page.getContent().get(0).getSourceName()).isEqualTo("Test Source");
    }

    @Test
    void listEvents_bySourceId() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        IncomingEvent event = buildEvent();
        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(eventRepository.findByIncomingSourceId(eq(sourceId), any()))
                .thenReturn(new PageImpl<>(List.of(event)));
        when(sourceRepository.findAllById(List.of(sourceId))).thenReturn(List.of(source));

        Page<IncomingEventResponse> page = service.listEvents(projectId, sourceId, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void getEvent_success() {
        IncomingEvent event = buildEvent();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        stubAccess();

        IncomingEventResponse response = service.getEvent(eventId, auth);

        assertThat(response.getId()).isEqualTo(eventId);
        assertThat(response.getMethod()).isEqualTo("POST");
        assertThat(response.getBodyRaw()).isEqualTo("{\"data\":1}");
    }

    @Test
    void getEvent_notFound() {
        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEvent(eventId, auth))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getEventAttempts_success() {
        IncomingEvent event = buildEvent();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        stubAccess();

        IncomingForwardAttempt attempt = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID()).incomingEventId(eventId).destinationId(destId)
                .attemptNumber(1).status(ForwardAttemptStatus.SUCCESS)
                .responseCode(200).createdAt(Instant.now())
                .build();
        when(forwardAttemptRepository.findByIncomingEventId(eq(eventId), any()))
                .thenReturn(new PageImpl<>(List.of(attempt)));

        Page<IncomingForwardAttemptResponse> page = service.getEventAttempts(eventId, auth, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getStatus()).isEqualTo(ForwardAttemptStatus.SUCCESS);
        assertThat(page.getContent().get(0).getResponseCode()).isEqualTo(200);
    }

    @Test
    void replayEvent_success() {
        IncomingEvent event = buildEvent();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        stubAccess();

        IncomingDestination dest = IncomingDestination.builder()
                .id(destId).incomingSourceId(sourceId)
                .url("https://example.com/hook")
                .authType(IncomingAuthType.NONE).enabled(true)
                .maxAttempts(5).timeoutSeconds(30).retryDelays("60")
                .build();
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of(dest));
        when(forwardAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxMessageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int replayed = service.replayEvent(eventId, auth);

        assertThat(replayed).isEqualTo(1);
        verify(forwardAttemptRepository).save(argThat(a ->
                a.getStatus() == ForwardAttemptStatus.PENDING && a.getAttemptNumber() == 1));
        verify(outboxMessageRepository).save(argThat(o ->
                o.getEventType().equals("IncomingForwardReplay")));
    }

    @Test
    void replayEvent_noDestinations_throws() {
        IncomingEvent event = buildEvent();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        stubAccess();
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.replayEvent(eventId, auth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No enabled destinations");
    }

    @Test
    void listEvents_bySourceId_crossProject_throws() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        UUID foreignSourceId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        IncomingSource foreignSource = IncomingSource.builder()
                .id(foreignSourceId).projectId(otherProjectId).name("Foreign Source")
                .slug("foreign").providerType(ProviderType.GENERIC)
                .status(IncomingSourceStatus.ACTIVE)
                .ingressPathToken("tok2").verificationMode(VerificationMode.NONE)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
        when(sourceRepository.findById(foreignSourceId)).thenReturn(Optional.of(foreignSource));

        assertThatThrownBy(() -> service.listEvents(projectId, foreignSourceId, PageRequest.of(0, 20)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Source does not belong to project");

        verify(eventRepository, never()).findByIncomingSourceId(any(), any());
    }

    @Test
    void replayEvent_notFound() {
        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replayEvent(eventId, auth))
                .isInstanceOf(NotFoundException.class);
    }
}
