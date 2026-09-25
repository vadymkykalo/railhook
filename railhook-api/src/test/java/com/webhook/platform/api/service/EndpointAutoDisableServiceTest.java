package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EndpointAutoDisableServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(72);
    private static final int MIN_FAILURES = 10;

    private EndpointRepository endpointRepository;
    private IncomingDestinationRepository destinationRepository;
    private IncomingSourceRepository sourceRepository;
    private EndpointAutoDisableNotifier notifier;
    private EndpointAutoDisableService service;

    @BeforeEach
    void setUp() {
        endpointRepository = mock(EndpointRepository.class);
        destinationRepository = mock(IncomingDestinationRepository.class);
        sourceRepository = mock(IncomingSourceRepository.class);
        notifier = mock(EndpointAutoDisableNotifier.class);

        lenient().when(endpointRepository.findAutoDisableCandidates(any(), anyInt(), any()))
                .thenReturn(List.of());
        lenient().when(destinationRepository.findAutoDisableCandidates(any(), anyInt(), any()))
                .thenReturn(List.of());
        lenient().when(endpointRepository.autoDisable(any(), any(), any())).thenReturn(1);
        lenient().when(destinationRepository.autoDisable(any(), any(), any())).thenReturn(1);

        service = newService(true, WINDOW, MIN_FAILURES);
    }

    private EndpointAutoDisableService newService(boolean enabled, Duration window, int minFailures) {
        return new EndpointAutoDisableService(endpointRepository, destinationRepository,
                sourceRepository, notifier, Clock.fixed(NOW, ZoneOffset.UTC),
                enabled, window.toHours(), minFailures, 100);
    }

    private Endpoint failingEndpoint(Instant failingSince, int failures) {
        return Endpoint.builder()
                .id(UUID.randomUUID())
                .organizationId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .url("https://receiver.test/hook")
                .enabled(true)
                .failingSince(failingSince)
                .consecutiveFailures(failures)
                .build();
    }

    @Nested
    @DisplayName("what it turns off")
    class Disabling {

        @Test
        @DisplayName("an endpoint failing for longer than the window is turned off, with a reason and a time")
        void disablesAfterTheWindow() {
            Endpoint endpoint = failingEndpoint(NOW.minus(Duration.ofHours(80)), 400);
            when(endpointRepository.findAutoDisableCandidates(any(), anyInt(), any()))
                    .thenReturn(List.of(endpoint));

            service.sweep();

            assertFalse(endpoint.getEnabled());
            assertEquals(NOW, endpoint.getAutoDisabledAt());
            assertNotNull(endpoint.getAutoDisabledReason());
            assertTrue(endpoint.getAutoDisabledReason().contains("72"));
            verify(endpointRepository).autoDisable(eq(endpoint.getId()), eq(NOW), any(String.class));
            assertEquals(NOW.minus(Duration.ofHours(80)), endpoint.getFailingSince());
            assertEquals(400, endpoint.getConsecutiveFailures());
        }

        @Test
        @DisplayName("the window and the minimum are what the query is asked for, not filtered afterwards")
        void asksTheQueryForBothConditions() {
            service.sweep();

            verify(endpointRepository).findAutoDisableCandidates(
                    eq(NOW.minus(WINDOW)), eq(MIN_FAILURES), any(Pageable.class));
        }

        @Test
        @DisplayName("a destination is turned off by the same sweep, on the same terms")
        void disablesDestinationsToo() {
            UUID sourceId = UUID.randomUUID();
            IncomingDestination destination = IncomingDestination.builder()
                    .id(UUID.randomUUID())
                    .organizationId(UUID.randomUUID())
                    .incomingSourceId(sourceId)
                    .url("https://sink.test/in")
                    .enabled(true)
                    .failingSince(NOW.minus(Duration.ofHours(80)))
                    .consecutiveFailures(99)
                    .build();
            when(destinationRepository.findAutoDisableCandidates(any(), anyInt(), any()))
                    .thenReturn(List.of(destination));
            when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(
                    IncomingSource.builder().id(sourceId).projectId(UUID.randomUUID()).build()));

            service.sweep();

            assertFalse(destination.getEnabled());
            assertEquals(NOW, destination.getAutoDisabledAt());
            verify(destinationRepository).autoDisable(eq(destination.getId()), eq(NOW), any(String.class));
        }
    }

    @Nested
    @DisplayName("what it tells the owner")
    class Notifying {

        @Test
        @DisplayName("every endpoint it turns off is announced exactly once")
        void announcesEachOnce() {
            Endpoint first = failingEndpoint(NOW.minus(Duration.ofHours(80)), 400);
            Endpoint second = failingEndpoint(NOW.minus(Duration.ofHours(90)), 12);
            when(endpointRepository.findAutoDisableCandidates(any(), anyInt(), any()))
                    .thenReturn(List.of(first, second));

            service.sweep();

            verify(notifier).endpointDisabled(first);
            verify(notifier).endpointDisabled(second);
        }

        @Test
        @DisplayName("an announcement that fails does not leave the endpoint on")
        void notificationFailureDoesNotUndoTheDisable() {
            Endpoint endpoint = failingEndpoint(NOW.minus(Duration.ofHours(80)), 400);
            when(endpointRepository.findAutoDisableCandidates(any(), anyInt(), any()))
                    .thenReturn(List.of(endpoint));
            doThrow(new IllegalStateException("mail server down"))
                    .when(notifier).endpointDisabled(any());

            service.sweep();

            assertFalse(endpoint.getEnabled());
            verify(endpointRepository).autoDisable(eq(endpoint.getId()), any(), any());
        }

        @Test
        @DisplayName("one endpoint that will not save does not stop the rest of the sweep")
        void oneFailureDoesNotStopTheSweep() {
            Endpoint bad = failingEndpoint(NOW.minus(Duration.ofHours(80)), 400);
            Endpoint good = failingEndpoint(NOW.minus(Duration.ofHours(80)), 400);
            when(endpointRepository.findAutoDisableCandidates(any(), anyInt(), any()))
                    .thenReturn(List.of(bad, good));
            when(endpointRepository.autoDisable(eq(bad.getId()), any(), any()))
                    .thenThrow(new IllegalStateException("row is locked"));

            service.sweep();

            verify(endpointRepository).autoDisable(eq(good.getId()), any(), any());
            verify(notifier).endpointDisabled(good);
            verify(notifier, never()).endpointDisabled(bad);
        }
    }

    @Nested
    @DisplayName("a deployment can turn it off")
    class Switch {

        @Test
        @DisplayName("with the feature off, nothing is even looked for")
        void disabledSweepsNothing() {
            EndpointAutoDisableService off = newService(false, WINDOW, MIN_FAILURES);

            off.sweep();

            verify(endpointRepository, never()).findAutoDisableCandidates(any(), anyInt(), any());
            verify(destinationRepository, never()).findAutoDisableCandidates(any(), anyInt(), any());
            verify(notifier, never()).endpointDisabled(any());
        }

        @Test
        @DisplayName("a zero window or a minimum below one is refused rather than disabling on one stray failure")
        void degenerateSettingsAreRefused() {
            assertThrows(IllegalArgumentException.class, () -> newService(true, Duration.ZERO, MIN_FAILURES));
            assertThrows(IllegalArgumentException.class, () -> newService(true, WINDOW, 0));
        }

    }

    @Nested
    @DisplayName("what it leaves alone")
    class LeavesAlone {

        @Test
        @DisplayName("an already auto-disabled endpoint is not announced twice")
        void doesNotReannounce() {
            Endpoint endpoint = failingEndpoint(NOW.minus(Duration.ofHours(80)), 400);
            endpoint.setEnabled(false);
            endpoint.setAutoDisabledAt(NOW.minus(Duration.ofHours(1)));
            when(endpointRepository.findAutoDisableCandidates(any(), anyInt(), any()))
                    .thenReturn(List.of(endpoint));

            service.sweep();

            verify(notifier, never()).endpointDisabled(any());
            verify(endpointRepository, never()).autoDisable(any(), any(), any());
            assertEquals(NOW.minus(Duration.ofHours(1)), endpoint.getAutoDisabledAt());
        }

        @Test
        @DisplayName("an endpoint re-enabled between the query and the write is not disabled again")
        void losesTheRaceAndSaysNothing() {
            // An owner clicking Enable between the read and the write must not be overwritten.
            Endpoint endpoint = failingEndpoint(NOW.minus(Duration.ofHours(80)), 400);
            when(endpointRepository.findAutoDisableCandidates(any(), anyInt(), any()))
                    .thenReturn(List.of(endpoint));
            when(endpointRepository.autoDisable(any(), any(), any())).thenReturn(0);

            service.sweep();

            verify(notifier, never()).endpointDisabled(any());
            assertTrue(endpoint.getEnabled());
        }

        @Test
        @DisplayName("a destination whose source has vanished is disabled but not announced")
        void destinationWithoutASourceIsStillDisabled() {
            UUID sourceId = UUID.randomUUID();
            IncomingDestination destination = IncomingDestination.builder()
                    .id(UUID.randomUUID())
                    .organizationId(UUID.randomUUID())
                    .incomingSourceId(sourceId)
                    .url("https://sink.test/in")
                    .enabled(true)
                    .failingSince(NOW.minus(Duration.ofHours(80)))
                    .consecutiveFailures(99)
                    .build();
            when(destinationRepository.findAutoDisableCandidates(any(), anyInt(), any()))
                    .thenReturn(List.of(destination));
            when(sourceRepository.findById(sourceId)).thenReturn(Optional.empty());

            service.sweep();

            assertFalse(destination.getEnabled());
            verify(notifier, never()).destinationDisabled(any(), any());
        }
    }
}
