package com.webhook.platform.api.service;

import com.sun.net.httpserver.HttpServer;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Endpoint.VerificationStatus;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a failed verification tells the person who pressed the button.
 *
 * <p>An endpoint pointed at a Railhook tunnel is verified through it, and a tunnel with no
 * {@code railhook tunnel} client connected answers 503. That reached the UI as
 * "Verification request failed: 503 Service Unavailable from POST https://…/tunnel/…" — true, and
 * no help: nothing said the fix was to start the tunnel.
 */
class EndpointVerificationFailureTest {

    private HttpServer server;
    private EndpointVerificationService service;
    private final EndpointRepository endpointRepository = mock(EndpointRepository.class);
    private final UUID projectId = UUID.randomUUID();
    private final UUID endpointId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        service = new EndpointVerificationService(endpointRepository, WebClient.builder(),
                true, List.of(), mock(PlatformTransactionManager.class));
        when(endpointRepository.save(any(Endpoint.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void anOfflineTunnel_saysSo() {
        answer("/tunnel/tun-abc", 503, "{\"error\":\"tunnel_offline\",\"message\":\"Tunnel is not connected\"}");
        pointEndpointAt("/tunnel/tun-abc");

        EndpointVerificationService.VerificationResult result = service.verify(projectId, endpointId);

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(EndpointVerificationService.FailureReason.TUNNEL_OFFLINE);
        assertThat(result.message()).contains("railhook tunnel");
        assertThat(result.endpoint().getVerificationStatus()).isEqualTo(VerificationStatus.FAILED);
    }

    @Test
    void anyOther503_isReportedAsItWas() {
        answer("/hook", 503, "maintenance");
        pointEndpointAt("/hook");

        EndpointVerificationService.VerificationResult result = service.verify(projectId, endpointId);

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isNull();
        assertThat(result.message()).startsWith("Verification request failed: 503");
    }

    private void answer(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private void pointEndpointAt(String path) {
        Endpoint endpoint = Endpoint.builder()
                .id(endpointId)
                .projectId(projectId)
                .url("http://127.0.0.1:" + server.getAddress().getPort() + path)
                .verificationStatus(VerificationStatus.PENDING)
                .verificationToken("whc_token")
                .build();
        when(endpointRepository.findByIdAndProjectId(endpointId, projectId)).thenReturn(Optional.of(endpoint));
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
    }
}
