package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.UsageDaily;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for UsageDailyAggregator.
 *
 * <p>{@code aggregateForProject} used to be {@code @Transactional} but was invoked from {@code
 * aggregateYesterday} via {@code this}, bypassing the Spring AOP proxy entirely -- so the
 * annotation did nothing, and the exists-check plus seven count queries each ran in their own
 * (implicit, autocommit) transaction against an inconsistent snapshot. It is fixed here by
 * driving a real {@code TransactionTemplate} explicitly, which does not depend on any proxy --
 * exactly what these tests exercise by calling {@code aggregateForProject} directly, the same
 * way self-invocation would.
 *
 * <p>{@code PlatformTransactionManager} is mocked but {@code TransactionTemplate} itself is
 * real (same pattern as {@code OutboxPublisherServiceTest}), so {@code getTransaction}/{@code
 * commit}/{@code rollback} calls on the mock are genuine evidence of what the real Spring
 * transaction infrastructure would have done.
 */
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

    /** aggregateForProject reads the organization off the ambient scope; the scheduler enters it. */
    private void aggregate(UUID projectId, LocalDate date) {
        TenantContext.runAs(ORG_ID, () -> aggregator.aggregateForProject(projectId, date));
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

    @Test
    void aggregateForProject_runsInsideOneTransaction_evenWhenCalledDirectly() {
        // Calling the method directly on the POJO (no Spring proxy involved at all) is exactly
        // the self-invocation scenario that made the old @Transactional a no-op. If this were
        // still driven by @Transactional, none of txManager's methods would ever be invoked
        // here, because there'd be no proxy to trigger them.
        when(usageDailyRepository.findByProjectIdAndDate(PROJECT_ID, DATE)).thenReturn(Optional.empty());
        stubCounts(10, 8, 6, 1, 1, 3, 2);
        when(usageDailyRepository.upsertIfAbsent(eq(ORG_ID), eq(PROJECT_ID), eq(DATE), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(1);

        aggregate(PROJECT_ID, DATE);

        verify(txManager, times(1)).getTransaction(any());
        verify(txManager, times(1)).commit(transactionStatus);
        verify(txManager, never()).rollback(any());
    }

    @Test
    void aggregateForProject_computesAndInsertsCorrectCounts() {
        when(usageDailyRepository.findByProjectIdAndDate(PROJECT_ID, DATE)).thenReturn(Optional.empty());
        stubCounts(10, 8, 6, 1, 1, 3, 2);
        when(usageDailyRepository.upsertIfAbsent(ORG_ID, PROJECT_ID, DATE, 10, 8, 6, 1, 1, 3, 2)).thenReturn(1);

        aggregate(PROJECT_ID, DATE);

        verify(usageDailyRepository).upsertIfAbsent(ORG_ID, PROJECT_ID, DATE, 10, 8, 6, 1, 1, 3, 2);
    }

    @Test
    void aggregateForProject_stampsTheOrganizationOnTheNativeInsert() {
        // usage_daily.organization_id went NOT NULL in V056, and this INSERT is native — outside
        // the @TenantId discriminator, which neither filters it nor fills it in. Losing the
        // value here is not a compile error and not a test failure anywhere else: it is a
        // constraint violation at 00:05, swallowed by the per-project catch in aggregateYesterday.
        when(usageDailyRepository.findByProjectIdAndDate(PROJECT_ID, DATE)).thenReturn(Optional.empty());
        stubCounts(1, 1, 1, 0, 0, 0, 0);
        when(usageDailyRepository.upsertIfAbsent(any(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(1);

        aggregate(PROJECT_ID, DATE);

        ArgumentCaptor<UUID> organizationId = ArgumentCaptor.forClass(UUID.class);
        verify(usageDailyRepository).upsertIfAbsent(organizationId.capture(), eq(PROJECT_ID), eq(DATE),
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        assertEquals(ORG_ID, organizationId.getValue());
    }

    @Test
    void aggregateForProject_alreadyAggregated_skipsWithoutQueryingCountsOrInserting() {
        // The cheap short-circuit path: a prior run (or a duplicate scheduler trigger) already
        // wrote this project/date. No point re-computing seven counts, and definitely no
        // second insert attempt.
        when(usageDailyRepository.findByProjectIdAndDate(PROJECT_ID, DATE))
                .thenReturn(Optional.of(UsageDaily.builder()
                        .projectId(PROJECT_ID).date(DATE).build()));

        aggregate(PROJECT_ID, DATE);

        verify(eventRepository, never()).countByProjectIdAndCreatedAtBetween(any(), any(), any());
        verify(usageDailyRepository, never()).upsertIfAbsent(any(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        // Still ran inside a (no-op) transaction -- the point is atomicity of the check, not
        // whether work happened.
        verify(txManager).commit(transactionStatus);
    }

    @Test
    void aggregateForProject_concurrentDuplicateRun_relinquishesToTheWinner_doesNotThrow() {
        // This run's own findByProjectIdAndDate check (inside its own transaction) sees nothing
        // yet, but by the time it INSERTs, a concurrent aggregation run (e.g. a second instance
        // without ShedLock, or ShedLock's lockAtLeastFor racing a slow run) has already
        // committed the row. The DB's UNIQUE (project_id, date) constraint -- not the
        // application-level exists-check -- is what actually prevents the duplicate: ON
        // CONFLICT DO NOTHING makes upsertIfAbsent return 0 instead of throwing.
        when(usageDailyRepository.findByProjectIdAndDate(PROJECT_ID, DATE)).thenReturn(Optional.empty());
        stubCounts(5, 4, 3, 1, 0, 2, 1);
        when(usageDailyRepository.upsertIfAbsent(eq(ORG_ID), eq(PROJECT_ID), eq(DATE), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(0);

        aggregate(PROJECT_ID, DATE);

        verify(usageDailyRepository, times(1)).upsertIfAbsent(eq(ORG_ID), eq(PROJECT_ID), eq(DATE), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(txManager).commit(transactionStatus);
        verify(txManager, never()).rollback(any());
    }

    @Test
    void aggregateForProject_midRunFailure_rollsBackAndNeverInserts() {
        when(usageDailyRepository.findByProjectIdAndDate(PROJECT_ID, DATE)).thenReturn(Optional.empty());
        when(eventRepository.countByProjectIdAndCreatedAtBetween(eq(PROJECT_ID), any(), any())).thenReturn(10L);
        when(deliveryRepository.countByProjectIdAndCreatedAtBetween(eq(PROJECT_ID), any(), any())).thenReturn(8L);
        // The third count query (SUCCESS) blows up mid-run, e.g. a transient DB error.
        when(deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(eq(PROJECT_ID), eq(DeliveryStatus.SUCCESS), any(), any()))
                .thenThrow(new RuntimeException("db unavailable"));

        assertThrows(RuntimeException.class, () -> aggregate(PROJECT_ID, DATE));

        // No partial row: the insert is the very last statement in the transaction, so a
        // failure before it means upsertIfAbsent is never even called.
        verify(usageDailyRepository, never()).upsertIfAbsent(any(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(txManager).rollback(transactionStatus);
        verify(txManager, never()).commit(any());
    }

    @Test
    void aggregateYesterday_continuesToNextProjectAfterOneFails() {
        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();
        when(projectRepository.findAll()).thenReturn(List.of(
                Project.builder().id(projectA).organizationId(ORG_ID).build(),
                Project.builder().id(projectB).organizationId(ORG_ID).build()));

        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(usageDailyRepository.findByProjectIdAndDate(eq(projectA), eq(yesterday)))
                .thenThrow(new RuntimeException("boom"));
        when(usageDailyRepository.findByProjectIdAndDate(eq(projectB), eq(yesterday)))
                .thenReturn(Optional.of(UsageDaily.builder()
                        .projectId(projectB).date(yesterday).build()));

        aggregator.aggregateYesterday();

        // Project A's failure must not prevent project B from being processed.
        verify(usageDailyRepository).findByProjectIdAndDate(projectA, yesterday);
        verify(usageDailyRepository).findByProjectIdAndDate(projectB, yesterday);
    }
}
