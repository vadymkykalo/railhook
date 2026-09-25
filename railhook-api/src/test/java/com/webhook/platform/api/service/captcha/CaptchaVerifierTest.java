package com.webhook.platform.api.service.captcha;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A CAPTCHA that silently stops verifying is the state it exists to prevent, so every doubt refuses.
class CaptchaVerifierTest {

    private HttpServer server;
    private String verifyUrl;
    private volatile String responseBody;
    private volatile int responseStatus;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/siteverify", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        verifyUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/siteverify";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private CaptchaVerifier turnstile() {
        return new TurnstileCaptchaVerifier(WebClient.builder().build(), new ObjectMapper(), verifyUrl, "test-secret");
    }

    @Test
    void anUnconfiguredDeploymentChallengesNobody() {
        CaptchaVerifier verifier = new DisabledCaptchaVerifier();

        assertTrue(verifier.verify(null, "203.0.113.7"));
        assertTrue(verifier.verify("anything", "203.0.113.7"));
        assertFalse(verifier.isEnabled(), "a deployment with no CAPTCHA should not claim to have one");
    }

    @Test
    void aGenuineTokenPasses() {
        responseStatus = 200;
        responseBody = "{\"success\":true}";

        assertTrue(turnstile().verify("valid-token", "203.0.113.7"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "200 | {\"success\":false,\"error-codes\":[\"invalid-input-response\"]}",
            "500 | {\"message\":\"upstream on fire\"}",
            "200 | {\"unexpected\":\"shape\"}",
    })
    void anythingButAnExplicitSuccessRefuses(int status, String body) {
        responseStatus = status;
        responseBody = body;

        assertFalse(turnstile().verify("valid-token", "203.0.113.7"));
    }

    @Test
    void aMissingTokenIsRefusedWithoutAskingTheProvider() {
        responseStatus = 500;
        responseBody = "should not be reached";

        assertFalse(turnstile().verify(null, "203.0.113.7"));
        assertFalse(turnstile().verify("   ", "203.0.113.7"));
    }

    @Test
    void anUnreachableProviderRefuses() {
        CaptchaVerifier verifier = new TurnstileCaptchaVerifier(
                WebClient.builder().build(), new ObjectMapper(), "http://127.0.0.1:1/siteverify",
                "test-secret");

        assertFalse(verifier.verify("valid-token", "203.0.113.7"));
    }
}
