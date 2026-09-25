package com.webhook.platform.api.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmailServiceTest {

    private JavaMailSender mailSender;
    private EmailService service;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        service = new EmailService(mailSender);
        ReflectionTestUtils.setField(service, "fromAddress", "noreply@railhook.test");
        ReflectionTestUtils.setField(service, "baseUrl", "https://railhook.test");

        logger = (Logger) LoggerFactory.getLogger(EmailService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private String loggedText() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private void emailEnabled(boolean enabled) {
        ReflectionTestUtils.setField(service, "emailEnabled", enabled);
    }

    private void environment(String env) {
        ReflectionTestUtils.setField(service, "appEnv", env);
    }

    private void smtpFails() {
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));
        doThrow(new MailSendException("relay refused")).when(mailSender).send(any(MimeMessage.class));
    }

    private MimeMessage captured() {
        MimeMessage message = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        return message;
    }

    private static String wire(MimeMessage message) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("with no SMTP configured")
    class WithoutSmtp {

        @Test
        @DisplayName("development still gets the link, because otherwise there is no way to reset a password")
        void developmentKeepsTheLink() {
            emailEnabled(false);
            environment("development");

            service.sendPasswordResetEmail("dev@railhook.test", "tok-dev");

            assertThat(loggedText()).contains("tok-dev");
        }

        @Test
        @DisplayName("production does not, and says where the link went instead")
        void productionWithholdsTheLink() {
            emailEnabled(false);
            environment("production");

            service.sendPasswordResetEmail("real@railhook.test", "tok-prod");

            assertThat(loggedText()).doesNotContain("tok-prod").contains("EMAIL_ENABLED");
        }
    }

    @Test
    @DisplayName("with SMTP configured, a failed send never logs its link as a consolation prize")
    void failedSendLogsNoLink() {
        emailEnabled(true);
        environment("production");
        smtpFails();

        service.sendPasswordResetEmail("real@railhook.test", "tok-fallback");
        service.sendVerificationEmail("real@railhook.test", "tok-verify");
        service.sendInviteEmail("real@railhook.test", "org-1", "tok-invite");

        assertThat(loggedText())
                .doesNotContain("tok-fallback")
                .doesNotContain("tok-verify")
                .doesNotContain("tok-invite")
                .contains("Mail password-reset to r***l@railhook.test failed");
    }

    // A real bounce could not be tied to a template or outcome; sends now log a masked recipient.
    @Nested
    @DisplayName("mail outcomes in the log")
    class Outcomes {

        private List<ILoggingEvent> infoAndAbove() {
            return appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)).toList();
        }

        @Test
        @DisplayName("a delivered mail logs its template, a masked recipient and success")
        void successIsLogged() {
            emailEnabled(true);
            environment("production");
            when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

            service.sendVerificationEmail("wheelet1228@gmail.con", "tok-secret-verify");

            assertThat(infoAndAbove()).extracting(ILoggingEvent::getFormattedMessage)
                    .contains("Sending mail verification to w***8@gmail.con",
                            "Mail verification to w***8@gmail.con sent");
            assertThat(loggedText()).doesNotContain("wheelet1228").doesNotContain("tok-secret-verify");
        }

        @Test
        @DisplayName("a refused mail logs the provider's error, still without the address or the token")
        void failureIsLoggedWithTheProviderError() {
            emailEnabled(true);
            environment("production");
            smtpFails();

            service.sendInviteEmail("teammate@acme.io", "org-1", "tok-secret-invite");

            assertThat(loggedText())
                    .contains("Mail invite to t***e@acme.io failed: relay refused")
                    .doesNotContain("teammate@acme.io")
                    .doesNotContain("tok-secret-invite");
        }

        @Test
        @DisplayName("every template goes through the same record")
        void everyTemplateIsNamed() {
            emailEnabled(true);
            environment("production");
            when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

            service.sendPasswordResetEmail("a@x.io", "t1");
            service.sendTemporaryPasswordEmail("a@x.io", "Pw!1");
            service.sendAlertEmail("a@x.io", "Endpoint down", "<p>body-marker</p>");
            service.sendEmailChangeConfirmation("new@x.io", "t2");
            service.sendEmailChangeNotice("old@x.io", "new@x.io", "t3");

            assertThat(loggedText())
                    .contains("Mail password-reset to a***@x.io sent")
                    .contains("Mail temporary-password to a***@x.io sent")
                    .contains("Mail alert to a***@x.io sent")
                    .contains("Mail email-change-confirmation to n***w@x.io sent")
                    .contains("Mail email-change-notice to o***d@x.io sent")
                    .doesNotContain("body-marker")
                    .doesNotContain("Pw!1");
        }

        @Test
        @DisplayName("the development log masks the recipient too, while keeping the link it exists for")
        void developmentMasksTheRecipient() {
            emailEnabled(false);
            environment("development");

            service.sendVerificationEmail("wheelet1228@gmail.con", "tok-dev-link");

            assertThat(loggedText()).contains("tok-dev-link").contains("w***8@gmail.con").doesNotContain("wheelet1228");
        }

        @ParameterizedTest
        @CsvSource(nullValues = "NULL", value = {
                "wheelet1228@gmail.con, w***8@gmail.con",
                "ab@x.io, a***b@x.io",
                "a@x.io, a***@x.io",
                "not-an-address, n***",
                "NULL, (none)",
        })
        void masking(String address, String masked) {
            assertThat(EmailService.maskRecipient(address)).isEqualTo(masked);
        }
    }

    @Nested
    @DisplayName("the message itself")
    class Content {

        // An HTML-only reset scores worse with spam filters, and a reset must not be filtered.
        @Test
        @DisplayName("a reset carries a plain-text alternative as well as the HTML")
        void resetIsMultipart() throws Exception {
            emailEnabled(true);
            environment("production");
            MimeMessage message = captured();

            service.sendPasswordResetEmail("real@railhook.test", "tok-multipart");

            assertThat(wire(message))
                    .contains("text/plain")
                    .contains("text/html")
                    .contains("multipart/alternative")
                    .contains("tok-multipart");
        }

        @Test
        @DisplayName("the temporary password is not repeated in plain text")
        void theTemporaryPasswordStaysInOnePlace() throws Exception {
            emailEnabled(true);
            environment("production");
            MimeMessage message = captured();

            service.sendTemporaryPasswordEmail("real@railhook.test", "TempPw!12345");

            assertThat(wire(message).split("TempPw!12345", -1).length - 1).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the onboarding mails")
    class Onboarding {

        @Test
        @DisplayName("the welcome goes to the new account, replies go to support, and the text part carries the quickstart")
        void welcome() throws Exception {
            emailEnabled(true);
            environment("production");
            ReflectionTestUtils.setField(service, "supportAddress", "support@railhook.test");
            MimeMessage message = captured();

            service.sendWelcomeEmail("new@acme.io");

            assertThat(message.getAllRecipients()).extracting(Object::toString).containsExactly("new@acme.io");
            assertThat(message.getReplyTo()).extracting(Object::toString).containsExactly("support@railhook.test");
            String sent = wire(message);
            assertThat(sent).contains("multipart/alternative").contains("text/plain").contains("text/html");
            assertThat(plainPart(sent))
                    .contains("https://railhook.test/docs/start/quickstart/")
                    .contains("https://railhook.test/docs/");
        }

        @Test
        @DisplayName("with email off nothing is sent, and the log says which mail and to whom")
        void disabledOnlyLogs() {
            emailEnabled(false);
            environment("production");

            service.sendWelcomeEmail("new@acme.io");
            service.sendOnboardingNudgeEmail("new@acme.io");

            verifyNoInteractions(mailSender);
            assertThat(loggedText())
                    .contains("onboarding-welcome")
                    .contains("onboarding-nudge")
                    .contains("n***w@acme.io")
                    .doesNotContain("new@acme.io");
        }

        private String plainPart(String wire) {
            int start = wire.indexOf("text/plain");
            int end = wire.indexOf("text/html");
            assertThat(start).isGreaterThanOrEqualTo(0);
            assertThat(end).isGreaterThan(start);
            return wire.substring(start, end);
        }
    }

    // The public contact form must never relay: it only writes to the support address.
    @Test
    @DisplayName("a contact-form message goes to support, with the visitor as Reply-To, as plain text")
    void contactMessageGoesToSupport() throws Exception {
        emailEnabled(true);
        ReflectionTestUtils.setField(service, "supportAddress", "support@railhook.test");
        MimeMessage message = captured();

        service.sendContactMessage("ada@example.com", "Ada", "sales", "<b>Two million</b> a month", "/pricing");

        assertThat(message.getAllRecipients()).extracting(Object::toString).containsExactly("support@railhook.test");
        assertThat(message.getReplyTo()).extracting(Object::toString).containsExactly("ada@example.com");
        assertThat(message.getContent().toString())
                .contains("<b>Two million</b> a month")
                .contains("/pricing");
        assertThat(message.getContentType()).startsWith("text/plain");
    }
}
