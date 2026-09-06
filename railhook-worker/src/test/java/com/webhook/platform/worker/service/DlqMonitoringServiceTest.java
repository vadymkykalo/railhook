package com.webhook.platform.worker.service;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaAdmin;

import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for DlqMonitoringService.
 *
 * <p>{@code webhook_dlq_depth} used to be computed as a Kafka latest-earliest offset
 * difference on the {@code deliveries.dlq} topic -- i.e. total retained volume, not actionable
 * backlog, since nothing consumes that topic. It never returned to 0 after remediation. It is
 * now backed by {@code DeliveryRepository#countDlqTotal()} (the same status column {@code
 * DlqService#retryDeliveries}/{@code #purgeAllDlq} mutate on the API side), and the old
 * Kafka-based computation lives on separately as the purely informational {@code
 * webhook_dlq_topic_retained_total}.
 *
 * <p>The Incoming direction now has the same pair, {@code incoming_forward_dlq_depth} and
 * {@code incoming_forward_dlq_topic_retained_total}. Before them a Forward that exhausted its
 * Retry Ladder wrote {@code status = DLQ} and nothing else, so no amount of abandoned Forwards
 * was visible to an operator. The tests below therefore care as much about the two directions
 * being <em>independent</em> -- one failing repository must not blank the other's gauge -- as
 * about the values themselves, since a shared try/catch would restore exactly the invisibility
 * this change removed.
 *
 * <p>DlqMonitoringService builds its own AdminClient internally (AdminClient.create(...))
 * rather than taking one as a collaborator, so the Kafka side can't be mocked the usual way --
 * instead this points a real KafkaAdmin at an address nothing is listening on, with a short
 * bounded timeout (an unbounded AdminClient call here would
 * starve every other @Scheduled job sharing the pool), and verifies the scheduled poll degrades
 * gracefully rather than hanging or throwing. The repositories, by contrast, are plain Spring
 * Data interfaces and are mocked directly.
 */
class DlqMonitoringServiceTest {

    private DlqMonitoringService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            assertDoesNotThrow(() -> service.close());
        }
    }

    private KafkaAdmin unreachableKafkaAdmin() throws Exception {
        int closedPort;
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            closedPort = serverSocket.getLocalPort();
        }
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + closedPort);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "500");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "500");
        props.put(AdminClientConfig.RETRIES_CONFIG, "0");
        props.put(AdminClientConfig.RECONNECT_BACKOFF_MS_CONFIG, "50");
        props.put(AdminClientConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, "100");
        KafkaAdmin admin = new KafkaAdmin(props);
        admin.setFatalIfBrokerNotAvailable(false);
        return admin;
    }

    @Test
    void registersBothGaugesForBothDirectionsAtConstruction() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        service = new DlqMonitoringService(kafkaAdminNoNetwork(), Mockito.mock(DeliveryRepository.class),
                Mockito.mock(IncomingForwardAttemptRepository.class), meterRegistry, 1);

        var actionable = meterRegistry.find("webhook_dlq_depth")
                .tag("topic", KafkaTopics.DELIVERIES_DLQ)
                .gauge();
        assertNotNull(actionable, "webhook_dlq_depth gauge must be registered at construction");
        assertEquals(0.0, actionable.value(), "actionable depth must start at 0 before the first poll");

        var retained = meterRegistry.find("webhook_dlq_topic_retained_total")
                .tag("topic", KafkaTopics.DELIVERIES_DLQ)
                .gauge();
        assertNotNull(retained, "webhook_dlq_topic_retained_total gauge must be registered at construction");
        assertEquals(0.0, retained.value());

        var incomingActionable = meterRegistry.find("incoming_forward_dlq_depth")
                .tag("topic", KafkaTopics.INCOMING_FORWARD_DLQ)
                .gauge();
        assertNotNull(incomingActionable, "incoming_forward_dlq_depth gauge must be registered at construction");
        assertEquals(0.0, incomingActionable.value(), "actionable depth must start at 0 before the first poll");

        var incomingRetained = meterRegistry.find("incoming_forward_dlq_topic_retained_total")
                .tag("topic", KafkaTopics.INCOMING_FORWARD_DLQ)
                .gauge();
        assertNotNull(incomingRetained,
                "incoming_forward_dlq_topic_retained_total gauge must be registered at construction");
        assertEquals(0.0, incomingRetained.value());
    }

    @Test
    void monitorDlqDepth_actionableDepth_reflectsDbDlqCount_independentOfKafka() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DeliveryRepository deliveryRepository = Mockito.mock(DeliveryRepository.class);
        IncomingForwardAttemptRepository forwardRepository = Mockito.mock(IncomingForwardAttemptRepository.class);
        when(deliveryRepository.countDlqTotal()).thenReturn(7L);
        when(forwardRepository.countDlqTotal()).thenReturn(4L);
        // Kafka is unreachable -- must not affect either DB-backed gauge at all.
        service = new DlqMonitoringService(unreachableKafkaAdmin(), deliveryRepository, forwardRepository,
                meterRegistry, 1);

        service.monitorDlqDepth();

        var actionable = meterRegistry.find("webhook_dlq_depth").gauge();
        assertNotNull(actionable);
        assertEquals(7.0, actionable.value());

        var incomingActionable = meterRegistry.find("incoming_forward_dlq_depth").gauge();
        assertNotNull(incomingActionable);
        assertEquals(4.0, incomingActionable.value());
    }

    @Test
    void monitorDlqDepth_actionableDepth_returnsToZero_afterBacklogCleared() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DeliveryRepository deliveryRepository = Mockito.mock(DeliveryRepository.class);
        IncomingForwardAttemptRepository forwardRepository = Mockito.mock(IncomingForwardAttemptRepository.class);
        when(deliveryRepository.countDlqTotal()).thenReturn(12L);
        when(forwardRepository.countDlqTotal()).thenReturn(3L);
        service = new DlqMonitoringService(kafkaAdminNoNetwork(), deliveryRepository, forwardRepository,
                meterRegistry, 1);

        service.monitorDlqDepth();
        var gauge = meterRegistry.find("webhook_dlq_depth").gauge();
        var incomingGauge = meterRegistry.find("incoming_forward_dlq_depth").gauge();
        assertNotNull(gauge);
        assertNotNull(incomingGauge);
        assertEquals(12.0, gauge.value(), "depth must reflect the DLQ backlog while it exists");
        assertEquals(3.0, incomingGauge.value(), "depth must reflect the Forward DLQ backlog while it exists");

        // Backlog worked through (retried/purged via DlqService on the API side) -- the next
        // poll must see the DB count go back to 0 -- the old Kafka-retention-based
        // computation never did this.
        when(deliveryRepository.countDlqTotal()).thenReturn(0L);
        when(forwardRepository.countDlqTotal()).thenReturn(0L);
        service.monitorDlqDepth();

        assertEquals(0.0, gauge.value(), "depth must return to 0 once the DLQ backlog is cleared");
        assertEquals(0.0, incomingGauge.value(), "depth must return to 0 once the Forward DLQ backlog is cleared");
    }

    @Test
    void monitorDlqDepth_failingDeliveryCount_stillReportsForwardBacklog() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DeliveryRepository deliveryRepository = Mockito.mock(DeliveryRepository.class);
        IncomingForwardAttemptRepository forwardRepository = Mockito.mock(IncomingForwardAttemptRepository.class);
        when(deliveryRepository.countDlqTotal()).thenThrow(new IllegalStateException("db down"));
        when(forwardRepository.countDlqTotal()).thenReturn(5L);
        service = new DlqMonitoringService(kafkaAdminNoNetwork(), deliveryRepository, forwardRepository,
                meterRegistry, 1);

        assertDoesNotThrow(() -> service.monitorDlqDepth());

        // The two directions must not share a failure path: a broken Outgoing query used to be
        // the whole poll, and letting it end the poll would leave the Incoming backlog exactly
        // as invisible as it was before it had a gauge at all.
        var incomingGauge = meterRegistry.find("incoming_forward_dlq_depth").gauge();
        assertNotNull(incomingGauge);
        assertEquals(5.0, incomingGauge.value(), "a failing Delivery count must not suppress the Forward gauge");
    }

    @Test
    void monitorDlqDepth_failingForwardCount_stillReportsDeliveryBacklog() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DeliveryRepository deliveryRepository = Mockito.mock(DeliveryRepository.class);
        IncomingForwardAttemptRepository forwardRepository = Mockito.mock(IncomingForwardAttemptRepository.class);
        when(deliveryRepository.countDlqTotal()).thenReturn(9L);
        when(forwardRepository.countDlqTotal()).thenThrow(new IllegalStateException("db down"));
        service = new DlqMonitoringService(kafkaAdminNoNetwork(), deliveryRepository, forwardRepository,
                meterRegistry, 1);

        assertDoesNotThrow(() -> service.monitorDlqDepth());

        var gauge = meterRegistry.find("webhook_dlq_depth").gauge();
        assertNotNull(gauge);
        assertEquals(9.0, gauge.value(), "a failing Forward count must not suppress the Delivery gauge");
    }

    @Test
    void monitorDlqDepth_topicRetainedDepth_brokerUnreachable_boundedTimeout_doesNotThrowOrHang() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        service = new DlqMonitoringService(unreachableKafkaAdmin(), Mockito.mock(DeliveryRepository.class),
                Mockito.mock(IncomingForwardAttemptRepository.class), meterRegistry, 1);

        long start = System.currentTimeMillis();
        assertDoesNotThrow(() -> service.monitorDlqDepth());
        long elapsedMs = System.currentTimeMillis() - start;

        // The whole point of the bounded .get() calls: this must return
        // quickly (well under the scheduler's own poll interval), not hang indefinitely
        // waiting on an unreachable broker. Both topics are now watched, so the bound has to
        // hold across both.
        org.junit.jupiter.api.Assertions.assertTrue(elapsedMs < 30_000,
                "monitorDlqDepth must respect the configured timeout, took " + elapsedMs + "ms");

        var gauge = meterRegistry.find("webhook_dlq_topic_retained_total").gauge();
        assertNotNull(gauge);
        assertEquals(0.0, gauge.value(), "retained depth must remain 0 (not update) when the broker call fails");

        var incomingGauge = meterRegistry.find("incoming_forward_dlq_topic_retained_total").gauge();
        assertNotNull(incomingGauge);
        assertEquals(0.0, incomingGauge.value(), "retained depth must remain 0 (not update) when the broker call fails");
    }

    @Test
    void close_shutsDownAdminClientWithoutThrowing() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        service = new DlqMonitoringService(kafkaAdminNoNetwork(), Mockito.mock(DeliveryRepository.class),
                Mockito.mock(IncomingForwardAttemptRepository.class), meterRegistry, 1);

        assertDoesNotThrow(() -> service.close());
        service = null; // already closed, don't double-close in tearDown
    }

    // Same short bounds as unreachableKafkaAdmin(). Without them AdminClient.close() blocks
    // on in-flight metadata calls for the full 60s default API timeout, once per watched
    // topic -- and there are two topics now, so leaving the defaults here cost minutes of
    // wall clock across this class for calls whose result no test even looks at.
    private KafkaAdmin kafkaAdminNoNetwork() {
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1");
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "500");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "500");
        props.put(AdminClientConfig.RETRIES_CONFIG, "0");
        props.put(AdminClientConfig.RECONNECT_BACKOFF_MS_CONFIG, "50");
        props.put(AdminClientConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, "100");
        KafkaAdmin admin = new KafkaAdmin(props);
        admin.setFatalIfBrokerNotAvailable(false);
        return admin;
    }
}
