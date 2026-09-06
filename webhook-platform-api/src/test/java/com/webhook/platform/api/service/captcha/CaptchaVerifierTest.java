package com.webhook.platform.api.service.captcha;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two things that decide whether a CAPTCHA is worth having.
 *
 * <p>The first is that switching it off is honest: an unconfigured deployment gets a verifier
 * that accepts everything, rather than a stub pretending to check. Self-hosting has nobody to
 * challenge, and sending its visitors to a third party to prove otherwise would be the worse
 * default.
 *
 * <p>The second is which way it fails. A provider that is unreachable, slow, or answering
 * nonsense must refuse the registration, not wave it through — a CAPTCHA that silently stops
 * verifying is the exact state it was added to prevent, and it would be invisible from the
 * outside. Every case below that is not an explicit {@code success: true} is a refusal.
 *
 * <p>Driven against a real local HTTP server rather than a mocked WebClient: what is being
 * asserted is the behaviour of an outbound call, and a mock would let a response-parsing bug
 * through while agreeing with itself.
 */
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
        // allow-private-ips is on because the stub is on loopback; the connector is otherwise
        // the same one every outbound client in the service uses.
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

    @Test
    void aRejectedTokenFails() {
        responseStatus = 200;
        responseBody = "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}";

        assertFalse(turnstile().verify("bad-token", "203.0.113.7"));
    }

    @Test
    void aMissingTokenIsRefusedWithoutAskingTheProvider() {
        responseStatus = 500;
        responseBody = "should not be reached";

        assertFalse(turnstile().verify(null, "203.0.113.7"));
        assertFalse(turnstile().verify("   ", "203.0.113.7"));
    }

    @Test
    void aProviderErrorRefusesRatherThanWavingThrough() {
        responseStatus = 500;
        responseBody = "{\"message\":\"upstream on fire\"}";

        assertFalse(turnstile().verify("valid-token", "203.0.113.7"));
    }

    @Test
    void anUnreachableProviderRefuses() {
        // The port is closed: the only honest answer to "is this caller genuine" when the thing
        // that knows cannot be asked is no.
        CaptchaVerifier verifier = new TurnstileCaptchaVerifier(
                WebClient.builder().build(), new ObjectMapper(), "http://127.0.0.1:1/siteverify",
                "test-secret");

        assertFalse(verifier.verify("valid-token", "203.0.113.7"));
    }

    @Test
    void aNonsenseResponseRefuses() {
        responseStatus = 200;
        responseBody = "{\"unexpected\":\"shape\"}";

        assertFalse(turnstile().verify("valid-token", "203.0.113.7"));
    }
}
