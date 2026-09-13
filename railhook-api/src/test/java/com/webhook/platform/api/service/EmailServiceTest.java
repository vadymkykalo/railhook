package com.webhook.platform.api.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.mail.internet.MimeMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What this service is allowed to write down.
 *
 * <p>Two of the three secrets it handles are short-lived and single-use, and
 * {@code sendTemporaryPasswordEmail} explains why that makes them safe to log when there is no
 * SMTP to send them through: without that line, a deployment with {@code app.email.enabled=false}
 * has no way to complete a password reset at all. That affordance is deliberate and stays.
 *
 * <p>What it never covered is the other two cases. A production deployment is not a workstation,
 * and a send that fails is not a deployment that asked for links in its log — it is one that
 * configured SMTP and had it blink.
 */
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
            // A reset token is short-lived and single-use, which is what makes logging it a
            // reasonable trade on a workstation. It is not one in production: anybody who can
            // read the log can take any account, and neither of those properties slows them
            // down. The operator is told what to fix rather than handed the secret.
            emailEnabled(false);
            environment("production");

            service.sendPasswordResetEmail("real@railhook.test", "tok-prod");

            assertThat(loggedText()).doesNotContain("tok-prod");
            assertThat(loggedText()).contains("EMAIL_ENABLED");
        }
    }

    @Nested
    @DisplayName("with SMTP configured, when the send fails")
    class SendFailure {

        @Test
        @DisplayName("the reset link is not logged as a consolation prize")
        void resetLinkIsNotLogged() {
            // This branch fires with app.email.enabled=true — a deployment that configured SMTP
            // and had it fail. It never asked for links in its log, and one flaky relay should
            // not be the difference. sendTemporaryPasswordEmail already refuses to do this.
            emailEnabled(true);
            environment("production");
            smtpFails();

            service.sendPasswordResetEmail("real@railhook.test", "tok-fallback");

            assertThat(loggedText()).doesNotContain("tok-fallback");
            assertThat(loggedText()).contains("Failed to send password reset email");
        }

        @Test
        @DisplayName("neither is the verification link")
        void verificationLinkIsNotLogged() {
            emailEnabled(true);
            environment("production");
            smtpFails();

            service.sendVerificationEmail("real@railhook.test", "tok-verify");

            assertThat(loggedText()).doesNotContain("tok-verify");
        }

        @Test
        @DisplayName("nor the invite link, which the caller is handed anyway")
        void inviteLinkIsNotLogged() {
            // inviteUrl() hands the same link back to the inviting owner, so logging it here
            // adds a copy in the least controlled place and nothing else.
            emailEnabled(true);
            environment("production");
            smtpFails();

            service.sendInviteEmail("real@railhook.test", "org-1", "tok-invite");

            assertThat(loggedText()).doesNotContain("tok-invite");
        }
    }

    /**
     * What actually leaves the building.
     *
     * These were HTML-only, with the link reachable solely through a styled anchor. Two
     * consequences, and the first is the one that costs you the message entirely: a mail with
     * no {@code text/plain} alternative scores worse with every spam filter that looks, and a
     * password reset is precisely the message that must not be filtered. The second is that a
     * client which does not render the button — or a person who would rather copy the address
     * than click a button in an email, which is the advice everyone is given — had nothing to
     * copy.
     */
    @Nested
    @DisplayName("the message itself")
    class Content {

        private MimeMessage captured() throws Exception {
            MimeMessage message = new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null);
            when(mailSender.createMimeMessage()).thenReturn(message);
            return message;
        }

        /**
         * Reads the message as it would go on the wire.
         *
         * <p>Walking the part tree needs a DataContentHandler for each type, and outside a
         * container there is none registered for text/html — the part comes back as a stream,
         * or as a wrapper that claims text/plain because it has no Content-Type of its own.
         * Serialising sidesteps all of it and asserts the thing that actually matters: what a
         * receiving server is handed.
         */
        private String wire(MimeMessage message) throws Exception {
            var out = new java.io.ByteArrayOutputStream();
            message.writeTo(out);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("a reset carries a plain-text alternative as well as the HTML")
        void resetIsMultipart() throws Exception {
            emailEnabled(true);
            environment("production");
            MimeMessage message = captured();

            service.sendPasswordResetEmail("real@railhook.test", "tok-multipart");

            String sent = wire(message);
            assertThat(sent)
                    .as("no text/plain part is a deliverability problem, not a styling one")
                    .contains("text/plain")
                    .contains("text/html")
                    .contains("multipart/alternative");
            assertThat(sent).contains("tok-multipart");
        }

        @Test
        @DisplayName("and shows the address, not only a button pointing at it")
        void resetShowsTheLinkAsText() throws Exception {
            emailEnabled(true);
            environment("production");
            MimeMessage message = captured();

            service.sendPasswordResetEmail("real@railhook.test", "tok-visible");

            // Three times at least: the plain part, the button's href, and the address
            // written out underneath it for someone who would rather copy than click.
            String sent = wire(message);
            assertThat(sent.split("tok-visible", -1).length - 1)
                    .as("the URL has to appear as readable text, not only inside an href")
                    .isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("verification and invite say the same thing the same way")
        void theOtherTwoMatch() throws Exception {
            emailEnabled(true);
            environment("production");

            MimeMessage verify = captured();
            service.sendVerificationEmail("real@railhook.test", "tok-verify");
            assertThat(wire(verify)).contains("text/plain").contains("tok-verify");

            MimeMessage invite = captured();
            service.sendInviteEmail("real@railhook.test", "org-1", "tok-invite");
            assertThat(wire(invite)).contains("text/plain").contains("tok-invite");
        }

        @Test
        @DisplayName("the temporary password is not repeated in plain text")
        void theTemporaryPasswordStaysInOnePlace() throws Exception {
            // The one exception. This password grants full, non-expiring access until it is
            // changed, and sendTemporaryPasswordEmail already refuses to log it for that
            // reason — putting a second copy in a text part would widen the same exposure.
            emailEnabled(true);
            environment("production");
            MimeMessage message = captured();

            service.sendTemporaryPasswordEmail("real@railhook.test", "TempPw!12345");

            // Exactly one copy, in the HTML body. A text alternative here would widen the
            // same exposure sendTemporaryPasswordEmail already refuses to widen by logging.
            assertThat(wire(message).split("TempPw!12345", -1).length - 1).isEqualTo(1);
        }
    }
}
