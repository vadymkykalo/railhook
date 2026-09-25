package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisTunnelCoordinatorTest {

    private RedissonClient redisson;
    private TunnelRegistry tunnelRegistry;
    private RedisTunnelCoordinator coordinator;
    private final Map<String, RTopic> topics = new LinkedHashMap<>();
    private RBucket<Object> bucket;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        tunnelRegistry = mock(TunnelRegistry.class);
        bucket = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(bucket);
        when(redisson.getTopic(anyString())).thenAnswer(inv ->
                topics.computeIfAbsent(inv.getArgument(0), name -> mock(RTopic.class)));
        coordinator = new RedisTunnelCoordinator(redisson, new ObjectMapper(), tunnelRegistry, new SimpleMeterRegistry());
        coordinator.startListening();
    }

    // The instance handling the DELETE rarely holds the CLI's socket, so the close must reach them all.
    @Test
    void disconnectForgetsTheOwnerAndBroadcastsTheSlug() {
        coordinator.disconnect("tun-gone");

        verify(redisson).getBucket("tunnel:owner:tun-gone");
        verify(bucket).delete();
        long broadcasts = topics.values().stream()
                .filter(topic -> mockingDetails(topic).getInvocations().stream()
                        .anyMatch(i -> i.getMethod().getName().equals("publish")
                                && "tun-gone".equals(i.getArgument(0))))
                .count();
        assertEquals(1, broadcasts, "one broadcast naming the slug");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aDisconnectBroadcastClosesTheSocketOnThisInstance() {
        ArgumentCaptor<MessageListener> listeners = ArgumentCaptor.forClass(MessageListener.class);
        for (RTopic topic : topics.values()) {
            verify(topic, atLeast(0)).addListener(eq(String.class), listeners.capture());
        }

        for (MessageListener listener : listeners.getAllValues()) {
            listener.onMessage("any", "tun-remote");
        }

        verify(tunnelRegistry).disconnect("tun-remote");
    }
}
