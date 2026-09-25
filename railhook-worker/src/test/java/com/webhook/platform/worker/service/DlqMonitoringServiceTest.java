package com.webhook.platform.worker.service;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** DLQ depth comes from the database per direction; one failing direction must not blank the other. */
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

        // The old Kafka-retention depth never returned to 0.
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

        // An unbounded AdminClient call starves every other @Scheduled job sharing the pool.
        assertTrue(elapsedMs < 30_000,
                "monitorDlqDepth must respect the configured timeout, took " + elapsedMs + "ms");

        var gauge = meterRegistry.find("webhook_dlq_topic_retained_total").gauge();
        assertNotNull(gauge);
        assertEquals(0.0, gauge.value(), "retained depth must remain 0 (not update) when the broker call fails");

        var incomingGauge = meterRegistry.find("incoming_forward_dlq_topic_retained_total").gauge();
        assertNotNull(incomingGauge);
        assertEquals(0.0, incomingGauge.value(), "retained depth must remain 0 (not update) when the broker call fails");
    }

    // Short bounds: with defaults AdminClient.close() blocks 60s per watched topic.
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
