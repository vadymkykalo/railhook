package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.UsageDaily;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository.ProjectRef;
import com.webhook.platform.api.domain.repository.UsageDailyRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// aggregateForProject was @Transactional but self-invoked, so each count ran in its own transaction.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UsageDailyAggregatorTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private UsageDailyRepository usageDailyRepository;
    @Mock
    private IncomingEventRepository incomingEventRepository;
    @Mock
    private IncomingForwardAttemptRepository incomingForwardAttemptRepository;
    @Mock
    private PlatformTransactionManager txManager;
    @Mock
    private TransactionStatus transactionStatus;

    private UsageDailyAggregator aggregator;

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 8, 21);

    private void aggregate(UUID projectId, LocalDate date) {
        TenantContext.runAs(ORG_ID, () -> aggregator.aggregateForProject(projectId, date));
    }

    private void recountIfUnsettled(UUID projectId, LocalDate date) {
        TenantContext.runAs(ORG_ID, () -> aggregator.recountIfUnsettled(projectId, date));
    }

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(transactionStatus);

        aggregator = new UsageDailyAggregator(
                projectRepository, eventRepository, deliveryRepository, usageDailyRepository,
                incomingEventRepository, incomingForwardAttemptRepository,
                new TransactionTemplate(txManager));
    }

    private void stubCounts(long events, long deliveries, long success, long failed, long dlq, long incoming, long forwards) {
        when(eventRepository.countByProjectIdAndCreatedAtBetween(eq(PROJECT_ID), any(), any())).thenReturn(events);
        when(deliveryRepository.countByProjectIdAndCreatedAtBetween(eq(PROJECT_ID), any(), any())).thenReturn(deliveries);
        when(deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(eq(PROJECT_ID), eq(DeliveryStatus.SUCCESS), any(), any())).thenReturn(success);
        when(deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(eq(PROJECT_ID), eq(DeliveryStatus.FAILED), any(), any())).thenReturn(failed);
        when(deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(eq(PROJECT_ID), eq(DeliveryStatus.DLQ), any(), any())).thenReturn(dlq);
        when(incomingEventRepository.countByProjectAndDateRange(eq(PROJECT_ID), any(), any())).thenReturn(incoming);
        when(incomingForwardAttemptRepository.countSuccessfulByProjectAndDateRange(eq(PROJECT_ID), any(), any())).thenReturn(forwards);
    }

    private static UsageDaily row(LocalDate date, long deliveries, long success, long failed, long dlq) {
        return UsageDaily.builder().projectId(PROJECT_ID).date(date)
                .deliveriesCount(deliveries).successfulDeliveries(success)
                .failedDeliveries(failed).dlqCount(dlq).build();
    }

    private void verifyWritten(LocalDate date, int times) {
        verify(usageDailyRepository, times(times)).upsert(any(), eq(PROJECT_ID), eq(date),
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void aggregateForProject_runsInsideOneTransaction_evenWhenCalledDirectly() {
        stubCounts(10, 8, 6, 1, 1, 3, 2);
        when(usageDailyRepository.upsert(eq(ORG_ID), eq(PROJECT_ID), eq(DATE), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(1);

        aggregate(PROJECT_ID, DATE);

        verify(txManager, times(1)).getTransaction(any());
        verify(txManager, times(1)).commit(transactionStatus);
        verify(txManager, never()).rollback(any());
    }

    @Test
    void aggregateForProject_stampsTheOrganizationOnTheNativeInsert() {
        // The native INSERT is outside @TenantId, so a lost organization_id fails silently at 00:05.
        stubCounts(1, 1, 1, 0, 0, 0, 0);
        when(usageDailyRepository.upsert(any(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(1);

        aggregate(PROJECT_ID, DATE);

        ArgumentCaptor<UUID> organizationId = ArgumentCaptor.forClass(UUID.class);
        verify(usageDailyRepository).upsert(organizationId.capture(), eq(PROJECT_ID), eq(DATE),
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        assertEquals(ORG_ID, organizationId.getValue());
    }

    @Test
    void aggregateForProject_recountsADayThatAlreadyHasARow() {
        // A day with a row was never counted again, losing Deliveries still retrying at 00:05.
        when(usageDailyRepository.findByProjectIdAndDate(PROJECT_ID, DATE))
                .thenReturn(Optional.of(row(DATE, 8, 5, 0, 0)));
        stubCounts(10, 8, 7, 0, 1, 3, 2);

        aggregate(PROJECT_ID, DATE);

        verify(usageDailyRepository).upsert(ORG_ID, PROJECT_ID, DATE, 10, 8, 7, 0, 1, 3, 2);
    }

    @Test
    void recountIfUnsettled_recountsADayWhoseDeliveriesWereStillRetrying() {
        when(usageDailyRepository.findByProjectIdAndDate(PROJECT_ID, DATE))
                .thenReturn(Optional.of(row(DATE, 8, 5, 1, 0)));
        stubCounts(10, 8, 6, 1, 1, 3, 2);

        recountIfUnsettled(PROJECT_ID, DATE);

        verify(usageDailyRepository).upsert(ORG_ID, PROJECT_ID, DATE, 10, 8, 6, 1, 1, 3, 2);
    }

    @Test
    void recountIfUnsettled_leavesASettledDayAlone() {
        when(usageDailyRepository.findByProjectIdAndDate(PROJECT_ID, DATE))
                .thenReturn(Optional.of(row(DATE, 8, 6, 1, 1)));

        recountIfUnsettled(PROJECT_ID, DATE);

        verify(eventRepository, never()).countByProjectIdAndCreatedAtBetween(any(), any(), any());
        verifyWritten(DATE, 0);
        verify(txManager).commit(transactionStatus);
    }

    @Test
    void recountIfUnsettled_writesADayThatHasNoRowYet() {
        when(usageDailyRepository.findByProjectIdAndDate(PROJECT_ID, DATE)).thenReturn(Optional.empty());
        stubCounts(4, 3, 3, 0, 0, 0, 0);

        recountIfUnsettled(PROJECT_ID, DATE);

        verify(usageDailyRepository).upsert(ORG_ID, PROJECT_ID, DATE, 4, 3, 3, 0, 0, 0, 0);
    }

    @Test
    void aggregateForProject_midRunFailure_rollsBackAndNeverWrites() {
        when(eventRepository.countByProjectIdAndCreatedAtBetween(eq(PROJECT_ID), any(), any())).thenReturn(10L);
        when(deliveryRepository.countByProjectIdAndCreatedAtBetween(eq(PROJECT_ID), any(), any())).thenReturn(8L);
        when(deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(eq(PROJECT_ID), eq(DeliveryStatus.SUCCESS), any(), any()))
                .thenThrow(new RuntimeException("db unavailable"));

        assertThrows(RuntimeException.class, () -> aggregate(PROJECT_ID, DATE));

        verifyWritten(DATE, 0);
        verify(txManager).rollback(transactionStatus);
        verify(txManager, never()).commit(any());
    }

    private static ProjectRef ref(UUID id) {
        return new ProjectRef() {
            @Override public UUID getId() {
                return id;
            }
            @Override public UUID getOrganizationId() {
                return ORG_ID;
            }
        };
    }

    @Test
    void aggregateYesterday_revisitsEveryDayWhoseDeliveriesCanStillSettle() {
        when(projectRepository.findLiveRefs(any())).thenReturn(List.of(ref(PROJECT_ID)));
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate dayBefore = today.minusDays(2);
        LocalDate settledOlder = today.minusDays(3);
        LocalDate unsettledOlder = today.minusDays(4);
        LocalDate missingOlder = today.minusDays(UsageDailyAggregator.RECOUNT_DAYS);
        LocalDate outOfWindow = today.minusDays(UsageDailyAggregator.RECOUNT_DAYS + 1);

        when(usageDailyRepository.findByProjectIdAndDate(PROJECT_ID, dayBefore))
                .thenReturn(Optional.of(row(dayBefore, 2, 2, 0, 0)));
        when(usageDailyRepository.findByProjectIdAndDate(PROJECT_ID, settledOlder))
                .thenReturn(Optional.of(row(settledOlder, 2, 2, 0, 0)));
        when(usageDailyRepository.findByProjectIdAndDate(PROJECT_ID, unsettledOlder))
                .thenReturn(Optional.of(row(unsettledOlder, 2, 1, 0, 0)));
        when(usageDailyRepository.findByProjectIdAndDate(PROJECT_ID, missingOlder)).thenReturn(Optional.empty());
        stubCounts(2, 2, 2, 0, 0, 0, 0);

        aggregator.aggregateYesterday();

        verifyWritten(yesterday, 1);
        verifyWritten(dayBefore, 1);
        verifyWritten(settledOlder, 0);
        verifyWritten(unsettledOlder, 1);
        verifyWritten(missingOlder, 1);
        verifyWritten(outOfWindow, 0);
    }

    @Test
    void aggregateYesterday_continuesToNextProjectAfterOneFails() {
        UUID projectA = UUID.randomUUID();
        when(projectRepository.findLiveRefs(any())).thenReturn(List.of(ref(projectA), ref(PROJECT_ID)), List.of());

        when(eventRepository.countByProjectIdAndCreatedAtBetween(eq(projectA), any(), any()))
                .thenThrow(new RuntimeException("boom"));
        when(usageDailyRepository.findByProjectIdAndDate(eq(projectA), any()))
                .thenThrow(new RuntimeException("boom"));
        stubCounts(1, 1, 1, 0, 0, 0, 0);

        aggregator.aggregateYesterday();

        verifyWritten(LocalDate.now().minusDays(1), 1);
    }

    @Test
    void aggregateYesterday_walksTheProjectsInPagesRatherThanLoadingThemAll() {
        // findAll() held every project in memory under a lock with a deadline.
        List<ProjectRef> fullPage = new ArrayList<>();
        for (int i = 0; i < UsageDailyAggregator.BATCH_SIZE; i++) {
            fullPage.add(ref(UUID.randomUUID()));
        }
        UUID onTheSecondPage = UUID.randomUUID();
        when(projectRepository.findLiveRefs(any()))
                .thenReturn(fullPage, List.of(ref(onTheSecondPage)));

        aggregator.aggregateYesterday();

        verify(eventRepository, atLeastOnce()).countByProjectIdAndCreatedAtBetween(eq(fullPage.get(0).getId()), any(), any());
        verify(eventRepository, atLeastOnce()).countByProjectIdAndCreatedAtBetween(eq(onTheSecondPage), any(), any());
        verify(projectRepository, times(2)).findLiveRefs(any());
    }

}
