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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweep that turns off a target which has answered nothing but failures for a window.
 *
 * <p>The worker keeps the run of failures on the target's row; this decides. It is split that
 * way because the decision needs the things the api owns — the alert, the mail, the audit of
 * who was told — and the worker has no channel to any of them but the database.
 *
 * <p>Deliberately a plain {@code *Test}: repositories are mocked, so it must run in the
 * no-Docker unit job. See {@code scripts/check-test-routing.sh}.
 */
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
            assertTrue(endpoint.getAutoDisabledReason().contains("72"),
                    "the reason has to name the window, or its owner cannot tell why now: "
                            + endpoint.getAutoDisabledReason());
            verify(endpointRepository).autoDisable(eq(endpoint.getId()), eq(NOW), any(String.class));
        }

        @Test
        @DisplayName("the run of failures is left alone, so the endpoint's own history survives the disable")
        void keepsTheRunOfFailures() {
            Endpoint endpoint = failingEndpoint(NOW.minus(Duration.ofHours(80)), 400);
            when(endpointRepository.findAutoDisableCandidates(any(), anyInt(), any()))
                    .thenReturn(List.of(endpoint));

            service.sweep();

            assertEquals(NOW.minus(Duration.ofHours(80)), endpoint.getFailingSince(),
                    "\"failing since\" is what the owner is told; clearing it here loses the answer");
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
            org.mockito.Mockito.doThrow(new IllegalStateException("mail server down"))
                    .when(notifier).endpointDisabled(any());

            service.sweep();

            assertFalse(endpoint.getEnabled(),
                    "the endpoint is dead either way; failing to say so does not make it alive");
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
        @DisplayName("a window of zero hours is refused at construction rather than disabling everything")
        void zeroWindowIsRefused() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> newService(true, Duration.ZERO, MIN_FAILURES));
        }

        @Test
        @DisplayName("a minimum below one is refused: one stray failure must never disable an endpoint")
        void zeroMinimumIsRefused() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> newService(true, WINDOW, 0));
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
            assertEquals(NOW.minus(Duration.ofHours(1)), endpoint.getAutoDisabledAt(),
                    "the time it was disabled is when it happened, not when the sweep last ran");
        }

        @Test
        @DisplayName("an endpoint re-enabled between the query and the write is not disabled again")
        void losesTheRaceAndSaysNothing() {
            // The sweep reads, then writes. An owner clicking Enable in between is exactly the
            // window a full save() would have overwritten — and they would have watched their
            // endpoint switch itself back off with no explanation.
            Endpoint endpoint = failingEndpoint(NOW.minus(Duration.ofHours(80)), 400);
            when(endpointRepository.findAutoDisableCandidates(any(), anyInt(), any()))
                    .thenReturn(List.of(endpoint));
            when(endpointRepository.autoDisable(any(), any(), any())).thenReturn(0);

            service.sweep();

            verify(notifier, never()).endpointDisabled(any());
            assertTrue(endpoint.getEnabled(), "the write did not apply, so nothing was decided");
        }

        @Test
        @DisplayName("nothing to do is not an error and writes nothing")
        void emptySweepWritesNothing() {
            service.sweep();

            verify(endpointRepository, never()).autoDisable(any(), any(), any());
            verify(notifier, never()).endpointDisabled(any());
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
