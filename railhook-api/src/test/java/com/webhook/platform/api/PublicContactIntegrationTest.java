package com.webhook.platform.api;

import com.webhook.platform.api.service.ContactMessageBudget;
import com.webhook.platform.api.service.EmailService;
import com.webhook.platform.api.service.captcha.CaptchaVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The message form on the public site: a visitor with no account writes to support, and the mail
 * arrives with their address as Reply-To so the answer is one click.
 *
 * <p>Anonymous and it sends mail, so it must not become a relay: it only ever writes to the
 * deployment's own support address, never to the address the visitor typed, and it sits behind
 * the same challenge and a per-address limit as the tester.
 */
public class PublicContactIntegrationTest extends AbstractIntegrationTest {

    private static final String VALID = """
            {"email":"ada@example.com","name":"Ada","topic":"sales",
             "message":"We send about two million events a month. Can we talk?",
             "page":"/pricing","captchaToken":"token"}
            """;

    @MockitoBean
    private CaptchaVerifier captchaVerifier;

    @MockitoBean
    private EmailService emailService;

    @MockitoBean
    private ContactMessageBudget contactMessageBudget;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void open() {
        when(captchaVerifier.verify(any(), anyString())).thenReturn(true);
        when(authRateLimiterService.allowContactMessage(anyString())).thenReturn(true);
        when(emailService.isContactAvailable()).thenReturn(true);
        when(contactMessageBudget.tryAcquire()).thenReturn(true);
    }

    private static MockHttpServletRequestBuilder send(String body) {
        return post("/api/v1/public/contact")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(request -> {
                    request.setRemoteAddr("198.51.100.7");
                    return request;
                });
    }

    @Test
    public void aMessageGoesToSupportWithTheVisitorAsReplyTo() throws Exception {
        mockMvc.perform(send(VALID)).andExpect(status().isAccepted());

        verify(emailService).sendContactMessage("ada@example.com", "Ada", "sales",
                "We send about two million events a month. Can we talk?", "/pricing");
    }

    @Test
    public void anAddressThatIsNotOneIsRefused() throws Exception {
        mockMvc.perform(send(VALID.replace("ada@example.com", "not-an-address")))
                .andExpect(status().isBadRequest());

        verify(emailService, never()).sendContactMessage(any(), any(), any(), any(), any());
    }

    @Test
    public void anEmptyMessageIsRefused() throws Exception {
        mockMvc.perform(send(VALID.replace("We send about two million events a month. Can we talk?", " ")))
                .andExpect(status().isBadRequest());

        verify(emailService, never()).sendContactMessage(any(), any(), any(), any(), any());
    }

    @Test
    public void anUnknownTopicIsRefused() throws Exception {
        mockMvc.perform(send(VALID.replace("\"sales\"", "\"<script>\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void aMessageTooLongIsRefused() throws Exception {
        String longMessage = "x".repeat(5001);
        mockMvc.perform(send(VALID.replace("We send about two million events a month. Can we talk?", longMessage)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void aFailedChallengeSendsNothing() throws Exception {
        when(captchaVerifier.verify(any(), anyString())).thenReturn(false);

        mockMvc.perform(send(VALID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("captcha_failed"));

        verify(emailService, never()).sendContactMessage(any(), any(), any(), any(), any());
    }

    @Test
    public void messagesAreLimitedPerAddress() throws Exception {
        when(authRateLimiterService.allowContactMessage("198.51.100.7")).thenReturn(false);

        mockMvc.perform(send(VALID))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate_limit_exceeded"));

        verify(emailService, never()).sendContactMessage(any(), any(), any(), any(), any());
    }

    @Test
    public void pastTheDailyCeilingNothingIsSent() throws Exception {
        when(contactMessageBudget.tryAcquire()).thenReturn(false);

        mockMvc.perform(send(VALID))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("contact_busy"));

        verify(emailService, never()).sendContactMessage(any(), any(), any(), any(), any());
    }

    @Test
    public void aFailedChallengeSpendsNoneOfTheDailyCeiling() throws Exception {
        when(captchaVerifier.verify(any(), anyString())).thenReturn(false);

        mockMvc.perform(send(VALID)).andExpect(status().isBadRequest());

        verify(contactMessageBudget, never()).tryAcquire();
    }

    @Test
    public void aDeploymentWithNoSupportAddressSaysSo() throws Exception {
        when(emailService.isContactAvailable()).thenReturn(false);

        mockMvc.perform(send(VALID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("contact_unavailable"));
    }
}
