package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.TunnelRequestLog;
import com.webhook.platform.api.domain.entity.TunnelSession;
import com.webhook.platform.api.domain.repository.TunnelRequestLogRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import com.webhook.platform.common.dto.tunnel.TunnelResponseMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

/**
 * Carries one public request through a developer's tunnel to their machine, and records what it
 * cost. Admission — is the tunnel up, is the caller within its rate limit, is the body small
 * enough — is decided here, so a refusal never reaches the CLI.
 */
@Service
@Slf4j
public class TunnelIngressService {

    private static final int MAX_BODY_SIZE = 512 * 1024;
    private static final int RATE_LIMIT_PER_SECOND = 10;

    /** Either the CLI answered, or it never got the request and this says why. */
    public sealed interface Outcome {

        record Answered(TunnelResponseMessage response) implements Outcome {
        }

        record Refused(String error, String message) implements Outcome {
        }

        record TimedOut() implements Outcome {
        }

        record Failed(String detail) implements Outcome {
        }
    }

    private final TunnelService tunnelService;
    private final RedisTunnelCoordinator redisTunnelCoordinator;
    private final RedisRateLimiterService rateLimiterService;
    private final TunnelRequestLogRepository requestLogRepository;
    private final TunnelBandwidthService bandwidthService;
    private final MeterRegistry meterRegistry;
    private final Executor tunnelMeteringExecutor;

    public TunnelIngressService(TunnelService tunnelService,
            RedisTunnelCoordinator redisTunnelCoordinator,
            RedisRateLimiterService rateLimiterService,
            TunnelRequestLogRepository requestLogRepository,
            TunnelBandwidthService bandwidthService,
            MeterRegistry meterRegistry,
            @Qualifier("tunnelMeteringExecutor") Executor tunnelMeteringExecutor) {
        this.tunnelService = tunnelService;
        this.redisTunnelCoordinator = redisTunnelCoordinator;
        this.rateLimiterService = rateLimiterService;
        this.requestLogRepository = requestLogRepository;
        this.bandwidthService = bandwidthService;
        this.meterRegistry = meterRegistry;
        this.tunnelMeteringExecutor = tunnelMeteringExecutor;
    }

    public Outcome forward(String slug, TunnelRequestMessage request, String body) {
        if (!redisTunnelCoordinator.isActiveInCluster(slug)) {
            return refuse("offline", "tunnel_offline", "Tunnel is not connected");
        }
        if (!rateLimiterService.tryAcquireForSlug(slug, RATE_LIMIT_PER_SECOND)) {
            log.warn("Rate limit exceeded for tunnel slug: {}", slug);
            return refuse("rate_limited", "rate_limit_exceeded", "Too many requests to this tunnel");
        }
        if (body != null && body.length() > MAX_BODY_SIZE) {
            return refuse("payload_too_large", "payload_too_large", "Request body exceeds maximum size");
        }

        long startMs = System.currentTimeMillis();
        TunnelResponseMessage response = redisTunnelCoordinator.forwardRequest(slug, request);
        int durationMs = (int) (System.currentTimeMillis() - startMs);

        int requestSize = body != null ? body.length() : 0;
        int responseSize = response != null && response.getBody() != null ? response.getBody().length() : 0;
        recordAsync(slug, request, requestSize, responseSize, response, durationMs);

        if (response == null) {
            outcomeCounter("timeout").increment();
            return new Outcome.TimedOut();
        }
        if (response.getError() != null) {
            outcomeCounter("error").increment();
            return new Outcome.Failed(response.getError());
        }
        outcomeCounter("success").increment();
        return new Outcome.Answered(response);
    }

    private Outcome refuse(String outcome, String error, String message) {
        outcomeCounter(outcome).increment();
        return new Outcome.Refused(error, message);
    }

    /**
     * Best-effort, off the response path.
     *
     * <p>A tunnel request authenticates nothing — the slug in the URL is the only thing naming an
     * organization — so the session lookup runs unscoped and everything after it runs inside the
     * organization that owns the tunnel. Without that, the save fails on an unresolved tenant and
     * metering quietly stops.
     */
    private void recordAsync(String slug, TunnelRequestMessage request, int requestSize,
            int responseSize, TunnelResponseMessage response, int durationMs) {
        tunnelMeteringExecutor.execute(() -> {
            try {
                TunnelSession session = TenantContext.callAsSystem(() -> tunnelService.getActiveBySlug(slug));
                TenantContext.runAs(session.getOrganizationId(), () -> {
                    bandwidthService.recordBytes(requestSize + responseSize);
                    requestLogRepository.save(TunnelRequestLog.builder()
                            .tunnelSessionId(session.getId())
                            .organizationId(session.getOrganizationId())
                            .slug(slug)
                            .requestId(request.getRequestId())
                            .method(request.getMethod())
                            .path(request.getPath())
                            .queryString(request.getQueryString())
                            .requestHeaders(request.getHeaders())
                            .requestBodySize(requestSize)
                            .responseStatus(response != null ? response.getStatusCode() : null)
                            .responseHeaders(response != null ? response.getHeaders() : null)
                            .responseBodySize(responseSize)
                            .durationMs(durationMs)
                            .error(response != null ? response.getError() : "timeout")
                            .build());
                });
            } catch (Exception e) {
                log.debug("Failed to log/meter tunnel request: slug={}, error={}", slug, e.getMessage());
            }
        });
    }

    private Counter outcomeCounter(String outcome) {
        return Counter.builder("tunnel_ingress_total").tag("outcome", outcome).register(meterRegistry);
    }
}
