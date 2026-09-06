package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import com.webhook.platform.common.dto.tunnel.TunnelResponseMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Redis-backed tunnel coordination for multi-instance deployments.
 * <p>
 * Each API instance gets a unique ID on startup. When a tunnel registers,
 * we store {@code slug → instanceId} in Redis. When a request arrives at
 * the wrong instance, we use Redis Pub/Sub to route it to the owning instance.
 * <p>
 * Fast path: if the slug is local, we skip Redis entirely.
 * Slow path: publish request → owning instance forwards via WebSocket → publishes response.
 */
@Slf4j
@Service
public class RedisTunnelCoordinator {

    private static final String SLUG_KEY_PREFIX = "tunnel:owner:";
    private static final Duration SLUG_TTL = Duration.ofMinutes(3);
    private static final String REQUEST_TOPIC_PREFIX = "tunnel:req:";
    private static final String RESPONSE_TOPIC_PREFIX = "tunnel:resp:";
    private static final int REMOTE_TIMEOUT_SECONDS = 25;

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final TunnelRegistry tunnelRegistry;
    private final String instanceId;

    /** Pending cross-instance responses: requestId → future */
    private final ConcurrentHashMap<String, CompletableFuture<TunnelResponseMessage>> remotePending = new ConcurrentHashMap<>();

    private int requestListenerId;
    private int responseListenerId;

    private final Counter localForwardCounter;
    private final Counter remoteForwardCounter;
    private final Counter remoteTimeoutCounter;
    private final Counter remoteErrorCounter;
    private final Timer forwardLatencyTimer;

    public RedisTunnelCoordinator(RedissonClient redissonClient,
                                  ObjectMapper objectMapper,
                                  TunnelRegistry tunnelRegistry,
                                  MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.tunnelRegistry = tunnelRegistry;
        this.instanceId = UUID.randomUUID().toString().substring(0, 8);

        this.localForwardCounter = Counter.builder("tunnel_forward_total")
                .tag("path", "local")
                .description("Tunnel requests forwarded via local WS")
                .register(meterRegistry);
        this.remoteForwardCounter = Counter.builder("tunnel_forward_total")
                .tag("path", "remote")
                .description("Tunnel requests forwarded via Redis Pub/Sub")
                .register(meterRegistry);
        this.remoteTimeoutCounter = Counter.builder("tunnel_remote_timeout_total")
                .description("Cross-instance tunnel requests that timed out")
                .register(meterRegistry);
        this.remoteErrorCounter = Counter.builder("tunnel_remote_error_total")
                .description("Cross-instance tunnel request errors")
                .register(meterRegistry);
        this.forwardLatencyTimer = Timer.builder("tunnel_forward_latency")
                .description("Tunnel request forwarding latency")
                .register(meterRegistry);
        Gauge.builder("tunnel_remote_pending", remotePending, ConcurrentHashMap::size)
                .description("Number of pending cross-instance tunnel requests")
                .register(meterRegistry);

        log.info("Tunnel coordinator initialized: instanceId={}", instanceId);
    }

    @PostConstruct
    public void startListening() {
        // Listen for cross-instance tunnel requests targeted at this instance
        RTopic requestTopic = redissonClient.getTopic(REQUEST_TOPIC_PREFIX + instanceId);
        requestListenerId = requestTopic.addListener(String.class, (channel, message) -> {
            handleRemoteRequest(message);
        });

        // Listen for cross-instance tunnel responses (for requests we sent)
        RTopic responseTopic = redissonClient.getTopic(RESPONSE_TOPIC_PREFIX + instanceId);
        responseListenerId = responseTopic.addListener(String.class, (channel, message) -> {
            handleRemoteResponse(message);
        });

        log.info("Tunnel coordinator listening on topics: req={}, resp={}",
                REQUEST_TOPIC_PREFIX + instanceId, RESPONSE_TOPIC_PREFIX + instanceId);
    }

    @PreDestroy
    public void stopListening() {
        try {
            redissonClient.getTopic(REQUEST_TOPIC_PREFIX + instanceId).removeListener(requestListenerId);
            redissonClient.getTopic(RESPONSE_TOPIC_PREFIX + instanceId).removeListener(responseListenerId);
        } catch (Exception e) {
            log.warn("Error cleaning up tunnel coordinator listeners: {}", e.getMessage());
        }
    }

    /**
     * Register a slug as owned by this instance. Called when a WS connection is established.
     */
    public void registerSlug(String slug) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(SLUG_KEY_PREFIX + slug);
            bucket.set(instanceId, SLUG_TTL);
            log.debug("Registered slug in Redis: slug={}, instance={}", slug, instanceId);
        } catch (Exception e) {
            log.warn("Failed to register slug in Redis (local-only mode): slug={}, error={}", slug, e.getMessage());
        }
    }

    /**
     * Unregister a slug from Redis. Called when a WS connection is closed.
     */
    public void unregisterSlug(String slug) {
        try {
            redissonClient.getBucket(SLUG_KEY_PREFIX + slug).delete();
            log.debug("Unregistered slug from Redis: slug={}", slug);
        } catch (Exception e) {
            log.warn("Failed to unregister slug from Redis: slug={}, error={}", slug, e.getMessage());
        }
    }

    /**
     * Refresh the TTL for a slug. Called on heartbeat.
     */
    public void refreshSlug(String slug) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(SLUG_KEY_PREFIX + slug);
            bucket.expire(SLUG_TTL);
        } catch (Exception e) {
            log.debug("Failed to refresh slug TTL: slug={}, error={}", slug, e.getMessage());
        }
    }

    /**
     * Check if a slug is active anywhere in the cluster.
     */
    public boolean isActiveInCluster(String slug) {
        // Fast path: local check
        if (tunnelRegistry.isActive(slug)) {
            return true;
        }
        // Slow path: check Redis
        try {
            RBucket<String> bucket = redissonClient.getBucket(SLUG_KEY_PREFIX + slug);
            return bucket.isExists();
        } catch (Exception e) {
            log.debug("Redis check failed for slug={}, falling back to local: {}", slug, e.getMessage());
            return false;
        }
    }

    /**
     * Forward a request, potentially cross-instance via Redis Pub/Sub.
     * Returns null if the tunnel is unreachable or times out.
     */
    public TunnelResponseMessage forwardRequest(String slug, TunnelRequestMessage request) {
        Timer.Sample sample = Timer.start();
        try {
            // Fast path: slug is local
            if (tunnelRegistry.isActive(slug)) {
                localForwardCounter.increment();
                return tunnelRegistry.forwardRequest(slug, request);
            }

            // Slow path: find owning instance and forward via Redis
            try {
                RBucket<String> bucket = redissonClient.getBucket(SLUG_KEY_PREFIX + slug);
                String ownerInstance = bucket.get();
                if (ownerInstance == null) {
                    log.warn("No instance owns slug={}", slug);
                    return null;
                }

                remoteForwardCounter.increment();
                return forwardRemote(ownerInstance, slug, request);
            } catch (Exception e) {
                remoteErrorCounter.increment();
                log.error("Cross-instance tunnel forward failed: slug={}, error={}", slug, e.getMessage());
                return null;
            }
        } finally {
            sample.stop(forwardLatencyTimer);
        }
    }

    /**
     * Send request to remote instance via Redis Pub/Sub and wait for response.
     */
    private TunnelResponseMessage forwardRemote(String targetInstance, String slug, TunnelRequestMessage request) {
        String requestId = request.getRequestId();
        CompletableFuture<TunnelResponseMessage> future = new CompletableFuture<>();
        remotePending.put(requestId, future);

        try {
            // Envelope: requestId, slug, callerInstance, request payload
            String payload = objectMapper.writeValueAsString(Map.of(
                    "requestId", requestId,
                    "slug", slug,
                    "callerInstance", instanceId,
                    "request", request
            ));

            RTopic topic = redissonClient.getTopic(REQUEST_TOPIC_PREFIX + targetInstance);
            long subscribers = topic.publish(payload);

            if (subscribers == 0) {
                log.warn("No subscribers for instance={}, slug may be stale", targetInstance);
                return null;
            }

            return future.get(REMOTE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            remoteTimeoutCounter.increment();
            log.warn("Remote tunnel request timed out: requestId={}, target={}", requestId, targetInstance);
            return null;
        } catch (Exception e) {
            log.error("Remote tunnel forward error: requestId={}, error={}", requestId, e.getMessage());
            return null;
        } finally {
            remotePending.remove(requestId);
        }
    }

    /**
     * Handle an incoming request from another instance. Forward through local WS and send response back.
     */
    private void handleRemoteRequest(String message) {
        try {
            var node = objectMapper.readTree(message);
            String requestId = node.get("requestId").asText();
            String slug = node.get("slug").asText();
            String callerInstance = node.get("callerInstance").asText();
            TunnelRequestMessage request = objectMapper.treeToValue(node.get("request"), TunnelRequestMessage.class);

            log.debug("Handling remote tunnel request: requestId={}, slug={}, from={}", requestId, slug, callerInstance);

            // Forward through local WS tunnel
            TunnelResponseMessage response = tunnelRegistry.forwardRequest(slug, request);

            // Send response back to caller instance
            String responsePayload = objectMapper.writeValueAsString(Map.of(
                    "requestId", requestId,
                    "response", response != null ? response : TunnelResponseMessage.builder()
                            .requestId(requestId)
                            .statusCode(502)
                            .error("tunnel_unavailable")
                            .build()
            ));

            RTopic responseTopic = redissonClient.getTopic(RESPONSE_TOPIC_PREFIX + callerInstance);
            responseTopic.publish(responsePayload);
        } catch (Exception e) {
            log.error("Error handling remote tunnel request: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle a response from a remote instance for a request we sent.
     */
    private void handleRemoteResponse(String message) {
        try {
            var node = objectMapper.readTree(message);
            String requestId = node.get("requestId").asText();
            TunnelResponseMessage response = objectMapper.treeToValue(node.get("response"), TunnelResponseMessage.class);

            CompletableFuture<TunnelResponseMessage> future = remotePending.remove(requestId);
            if (future != null) {
                future.complete(response);
            } else {
                log.warn("No pending future for remote response: requestId={}", requestId);
            }
        } catch (Exception e) {
            log.error("Error handling remote tunnel response: {}", e.getMessage(), e);
        }
    }

    public String getInstanceId() {
        return instanceId;
    }
}
