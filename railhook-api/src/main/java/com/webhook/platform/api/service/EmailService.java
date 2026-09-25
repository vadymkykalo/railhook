package com.webhook.platform.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.email.from:noreply@example.com}")
    private String fromAddress;

    @Value("${app.email.support-address:}")
    private String supportAddress;

    @Value("${app.base-url:http://localhost:5173}")
    private String baseUrl;

    @Value("${app.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${app.env:development}")
    private String appEnv;

    public EmailService(@Autowired(required = false) JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** When false every send only logs, so a flow that needs the mail to arrive must take another path. */
    public boolean isEnabled() {
        return emailEnabled;
    }

    /** E.g. {@code w***8@gmail.con}: enough to match a bounce, not enough to harvest the address. */
    static String maskRecipient(String address) {
        if (address == null || address.isBlank()) {
            return "(none)";
        }
        int at = address.lastIndexOf('@');
        if (at < 0) {
            return address.charAt(0) + "***";
        }
        String mailbox = address.substring(0, at);
        String domain = address.substring(at);
        if (mailbox.length() <= 1) {
            return mailbox + "***" + domain;
        }
        return mailbox.charAt(0) + "***" + mailbox.charAt(mailbox.length() - 1) + domain;
    }

    // Links are bearer credentials: logging them is only acceptable outside production.
    private boolean mayLogLinkInstead() {
        return !"production".equalsIgnoreCase(appEnv);
    }

    private void explainWithheldLink(String what, String to) {
        log.warn("{} for {} was not sent and will not be logged: APP_ENV=production with "
                + "EMAIL_ENABLED=false. Configure SMTP, or the link cannot reach anyone.", what, maskRecipient(to));
    }

    private void logLinkInstead(String banner, String to, String label, String url) {
        log.info("========== {} ==========", banner);
        log.info("To: {}", maskRecipient(to));
        log.info("{}: {}", label, url);
        log.info("=========================================");
    }

    @FunctionalInterface
    private interface Send {
        void run() throws Exception;
    }

    // Never logs a body, subject or link (links are bearer credentials), and never throws.
    private void deliver(String template, String to, Send send) {
        String masked = maskRecipient(to);
        log.info("Sending mail {} to {}", template, masked);
        try {
            send.run();
            log.info("Mail {} to {} sent", template, masked);
        } catch (Exception e) {
            String providerError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (to != null && !to.isBlank()) {
                providerError = providerError.replace(to, masked);
            }
            log.error("Mail {} to {} failed: {}", template, masked, providerError);
        }
    }

    public void sendVerificationEmail(String to, String token) {
        String verifyUrl = baseUrl + "/verify-email?token=" + token;

        if (!emailEnabled) {
            if (!mayLogLinkInstead()) {
                explainWithheldLink("An email verification link", to);
                return;
            }
            logLinkInstead("EMAIL VERIFICATION", to, "Verify URL", verifyUrl);
            return;
        }

        deliver("verification", to, () -> sendBoth(to, "Verify your email — Railhook",
                """
                Thanks for signing up for Railhook.

                Verify your email address by opening this link:
                %s

                The link expires in 24 hours. If you didn't create an account, ignore this.
                """.formatted(verifyUrl),
                buildVerificationHtml(verifyUrl)));
    }

    public void sendPasswordResetEmail(String to, String token) {
        String resetUrl = baseUrl + "/reset-password?token=" + token;

        if (!emailEnabled) {
            if (!mayLogLinkInstead()) {
                explainWithheldLink("A password reset link", to);
                return;
            }
            logLinkInstead("PASSWORD RESET", to, "Reset URL", resetUrl);
            return;
        }

        deliver("password-reset", to, () -> sendBoth(to, "Reset your password — Railhook",
                """
                We received a request to reset the password for your Railhook account.

                Set a new password here:
                %s

                The link expires in 1 hour. If you didn't ask for this, ignore this email —
                your password has not changed.
                """.formatted(resetUrl),
                buildPasswordResetHtml(resetUrl)));
    }

    public void sendEmailChangeConfirmation(String to, String token) {
        String confirmUrl = baseUrl + "/confirm-email-change?token=" + token;

        if (!emailEnabled) {
            if (!mayLogLinkInstead()) {
                explainWithheldLink("An email change confirmation link", to);
                return;
            }
            logLinkInstead("EMAIL CHANGE CONFIRMATION", to, "Confirm URL", confirmUrl);
            return;
        }

        deliver("email-change-confirmation", to, () -> sendBoth(to, "Confirm your new email — Railhook",
                """
                Someone asked to use this address for a Railhook account.

                If that was you, confirm it here:
                %s

                The link expires in 24 hours. Until then the account keeps its current address.
                If it wasn't you, ignore this email and nothing changes.
                """.formatted(confirmUrl),
                buildEmailChangeConfirmationHtml(confirmUrl)));
    }

    /** Sent to the old address. The cancel link needs no session, since the owner may no longer have one. */
    public void sendEmailChangeNotice(String to, String newAddress, String cancelToken) {
        String cancelUrl = baseUrl + "/cancel-email-change?token=" + cancelToken;
        String maskedNew = maskRecipient(newAddress);
        String help = supportAddress == null || supportAddress.isBlank()
                ? "contact the administrator of this Railhook installation"
                : "write to " + supportAddress;

        if (!emailEnabled) {
            if (!mayLogLinkInstead()) {
                explainWithheldLink("An email change notice", to);
                return;
            }
            logLinkInstead("EMAIL CHANGE NOTICE", to, "Cancel URL", cancelUrl);
            return;
        }

        deliver("email-change-notice", to, () -> sendBoth(to, "Your Railhook email is being changed",
                """
                Someone asked to change the email address of your Railhook account to %s.

                If that was you, there is nothing to do: the change completes when the new address
                is confirmed.

                If it wasn't you, cancel it here — this also signs out every session:
                %s

                Then reset your password. If you need help, %s.
                """.formatted(maskedNew, cancelUrl, help),
                buildEmailChangeNoticeHtml(maskedNew, cancelUrl, help)));
    }

    /** Also returned to the owner: with email off, a link passed on by hand is the only way the invite arrives. */
    public String inviteUrl(String orgId, String inviteToken) {
        return baseUrl + "/accept-invite?token=" + inviteToken + "&orgId=" + orgId;
    }

    public void sendInviteEmail(String to, String orgId, String inviteToken) {
        String inviteUrl = inviteUrl(orgId, inviteToken);

        if (!emailEnabled) {
            if (!mayLogLinkInstead()) {
                explainWithheldLink("An invite link", to);
                return;
            }
            logLinkInstead("MEMBER INVITE", to, "Invite URL", inviteUrl);
            return;
        }

        // Not logged: inviteUrl() already hands the link to the owner.
        deliver("invite", to, () -> sendBoth(to, "You've been invited to join an organization — Railhook",
                """
                You've been invited to join an organization on Railhook.

                Accept the invitation here:
                %s
                """.formatted(inviteUrl),
                buildInviteHtml(inviteUrl)));
    }

    // Never logs the password: unlike a link it grants full access until changed.
    public void sendTemporaryPasswordEmail(String to, String tempPassword) {
        if (!emailEnabled) {
            log.info("========== TEMP PASSWORD EMAIL SKIPPED (app.email.enabled=false) ==========");
            log.info("To: {} — temporary password was generated but NOT logged or emailed.", maskRecipient(to));
            log.info("Use POST /api/v1/auth/forgot-password to issue a usable (loggable) reset token instead.");
            log.info("=============================================================================");
            return;
        }

        deliver("temporary-password", to, () -> {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject("Your temporary password — Railhook");
            helper.setText(buildTemporaryPasswordHtml(tempPassword), true);
            mailSender.send(message);
        });
    }

    public void sendAlertEmail(String to, String subject, String htmlBody) {
        if (!emailEnabled) {
            log.info("========== ALERT EMAIL ==========");
            log.info("To: {}", maskRecipient(to));
            log.info("Subject: {}", subject);
            log.info("=================================");
            return;
        }

        deliver("alert", to, () -> {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
        });
    }

    /** Replies go to the support address. Nothing here is a credential, so with email off it only logs. */
    public void sendWelcomeEmail(String to) {
        String quickstartUrl = baseUrl + "/docs/start/quickstart/";
        String stripeUrl = baseUrl + "/docs/guides/stripe-webhooks/";
        String githubUrl = baseUrl + "/docs/guides/github-webhooks/";
        if (!emailEnabled) {
            logNotSent("onboarding-welcome", to);
            return;
        }
        deliver("onboarding-welcome", to, () -> sendBoth(to, "Welcome to Railhook", replyAddress(),
                """
                Hi,

                I'm Vadym, and I built Railhook. Thanks for signing up.

                Three things worth doing first:

                1. Create a project and send your first event. The quickstart takes a few minutes:
                %s

                2. Receive webhooks from a provider you already use, such as Stripe:
                %s
                or GitHub:
                %s

                3. If anything is unclear or does not work, reply to this email. It reaches me.

                Vadym
                """.formatted(quickstartUrl, stripeUrl, githubUrl),
                buildWelcomeHtml(quickstartUrl, stripeUrl, githubUrl)));
    }

    public void sendOnboardingNudgeEmail(String to) {
        String quickstartUrl = baseUrl + "/docs/start/quickstart/";
        if (!emailEnabled) {
            logNotSent("onboarding-nudge", to);
            return;
        }
        deliver("onboarding-nudge", to, () -> sendBoth(to, "Stuck? The 2-minute path to a first event", replyAddress(),
                """
                Hi,

                Vadym from Railhook again. Your account has not sent or received an event yet,
                which usually means something got in the way.

                The shortest path: create a project, add an endpoint, send one event. It takes
                about two minutes:
                %s

                If you got stuck somewhere, reply and tell me where. I read every reply.

                Vadym
                """.formatted(quickstartUrl),
                buildOnboardingNudgeHtml(quickstartUrl)));
    }

    private String replyAddress() {
        return supportAddress == null || supportAddress.isBlank() ? null : supportAddress;
    }

    private void logNotSent(String template, String to) {
        log.info("Mail {} to {} not sent: EMAIL_ENABLED=false", template, maskRecipient(to));
    }

    public boolean isContactAvailable() {
        return supportAddress != null && !supportAddress.isBlank();
    }

    // The visitor is only the Reply-To, never a recipient, so the form cannot be used as a relay.
    @Async
    public void sendContactMessage(String replyTo, String name, String topic, String message, String page) {
        String who = name == null || name.isBlank() ? replyTo : oneLine(name);
        String subject = "[Railhook " + topic + "] Message from " + who;
        if (!emailEnabled) {
            log.info("========== CONTACT MESSAGE ==========");
            log.info("From: {}", maskRecipient(replyTo));
            log.info("Topic: {}", topic);
            log.info("=====================================");
            return;
        }
        String body = message
                + "\n\n--\n"
                + "From: " + who + " <" + replyTo + ">\n"
                + "Topic: " + topic + "\n"
                + "Sent from: " + baseUrl + (page == null ? "" : oneLine(page)) + "\n";
        deliver("contact", supportAddress, () -> {
            var mail = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(mail, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(supportAddress);
            helper.setReplyTo(replyTo);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(mail);
        });
    }

    /** No line breaks, so a visitor cannot inject a header. */
    private static String oneLine(String value) {
        return value.replaceAll("[\\r\\n\\t]+", " ").strip();
    }

    private String buildInviteHtml(String inviteUrl) {
        return """
            <div style="font-family: sans-serif; max-width: 480px; margin: 0 auto; padding: 32px;">
                <h2 style="color: #111;">You're invited!</h2>
                <p style="color: #555; line-height: 1.5;">
                    You've been invited to join an organization on Railhook.
                    Click the button below to accept the invitation.
                </p>
                <a href="%s"
                   style="display: inline-block; padding: 12px 24px; background: #111; color: #fff;
                          text-decoration: none; border-radius: 6px; margin: 16px 0;">
                    Accept Invitation
                </a>
%s                <p style="color: #999; font-size: 12px; margin-top: 24px;">
                    This invitation expires in 48 hours. If you didn't expect this, you can safely ignore it.
                </p>
            </div>
            """.formatted(inviteUrl, linkFallback(inviteUrl));
    }

    private String buildTemporaryPasswordHtml(String tempPassword) {
        String loginUrl = baseUrl + "/login";
        return """
            <div style="font-family: sans-serif; max-width: 480px; margin: 0 auto; padding: 32px;">
                <h2 style="color: #111;">Your Railhook account is ready</h2>
                <p style="color: #555; line-height: 1.5;">
                    An organization invited you to Railhook and an account was created for you.
                    Use the temporary password below to sign in, then change it right away.
                </p>
                <p style="font-family: monospace; font-size: 18px; background: #f4f4f4;
                          padding: 12px 16px; border-radius: 6px; display: inline-block;">
                    %s
                </p>
                <p style="margin-top: 16px;">
                    <a href="%s"
                       style="display: inline-block; padding: 12px 24px; background: #111; color: #fff;
                              text-decoration: none; border-radius: 6px;">
                        Sign in
                    </a>
                </p>
                <p style="color: #999; font-size: 12px; margin-top: 24px;">
                    If you didn't expect this invitation, you can safely ignore this email.
                </p>
            </div>
            """.formatted(tempPassword, loginUrl);
    }


    /** Without a text/plain part spam filters score the mail worse, and a password reset must not be filtered. */
    private void sendBoth(String to, String subject, String plain, String html) throws Exception {
        sendBoth(to, subject, null, plain, html);
    }

    private void sendBoth(String to, String subject, String replyTo, String plain, String html) throws Exception {
        var message = mailSender.createMimeMessage();
        var helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromAddress);
        helper.setTo(to);
        if (replyTo != null) {
            helper.setReplyTo(replyTo);
        }
        helper.setSubject(subject);
        helper.setText(plain, html);
        mailSender.send(message);
    }

    /** Written out under the button for clients that do not style it and people who copy rather than click. */
    private static String linkFallback(String url) {
        return """
            <p style="color: #777; font-size: 12px; line-height: 1.6; margin-top: 8px;">
                Or copy this address into your browser:<br>
                <span style="color: #555; word-break: break-all;">%s</span>
            </p>
            """.formatted(url);
    }

    private String buildWelcomeHtml(String quickstartUrl, String stripeUrl, String githubUrl) {
        return """
            <div style="font-family: sans-serif; max-width: 520px; margin: 0 auto; padding: 32px; color: #333; line-height: 1.6;">
                <p>Hi,</p>
                <p>I'm Vadym, and I built Railhook. Thanks for signing up.</p>
                <p>Three things worth doing first:</p>
                <ol style="padding-left: 20px;">
                    <li style="margin-bottom: 8px;">
                        Create a project and send your first event. The
                        <a href="%1$s" style="color: #1D4BFF;">quickstart</a> takes a few minutes.
                    </li>
                    <li style="margin-bottom: 8px;">
                        Receive webhooks from a provider you already use, such as
                        <a href="%2$s" style="color: #1D4BFF;">Stripe</a> or
                        <a href="%3$s" style="color: #1D4BFF;">GitHub</a>.
                    </li>
                    <li style="margin-bottom: 8px;">
                        If anything is unclear or does not work, reply to this email. It reaches me.
                    </li>
                </ol>
                <p>Vadym</p>
%4$s            </div>
            """.formatted(quickstartUrl, stripeUrl, githubUrl, linkFallback(quickstartUrl));
    }

    private String buildOnboardingNudgeHtml(String quickstartUrl) {
        return """
            <div style="font-family: sans-serif; max-width: 520px; margin: 0 auto; padding: 32px; color: #333; line-height: 1.6;">
                <p>Hi,</p>
                <p>Vadym from Railhook again. Your account has not sent or received an event yet,
                   which usually means something got in the way.</p>
                <p>The shortest path: create a project, add an endpoint, send one event. It takes
                   about two minutes with the <a href="%1$s" style="color: #1D4BFF;">quickstart</a>.</p>
                <p>If you got stuck somewhere, reply and tell me where. I read every reply.</p>
                <p>Vadym</p>
%2$s            </div>
            """.formatted(quickstartUrl, linkFallback(quickstartUrl));
    }

    private String buildPasswordResetHtml(String resetUrl) {
        return """
            <div style="font-family: sans-serif; max-width: 480px; margin: 0 auto; padding: 32px;">
                <h2 style="color: #111;">Reset your password</h2>
                <p style="color: #555; line-height: 1.5;">
                    We received a request to reset the password for your Railhook account.
                    Click the button below to set a new password.
                </p>
                <a href="%s"
                   style="display: inline-block; padding: 12px 24px; background: #111; color: #fff;
                          text-decoration: none; border-radius: 6px; margin: 16px 0;">
                    Reset Password
                </a>
%s                <p style="color: #999; font-size: 12px; margin-top: 24px;">
                    If you didn't request a password reset, you can safely ignore this email.
                    This link expires in 1 hour.
                </p>
            </div>
            """.formatted(resetUrl, linkFallback(resetUrl));
    }

    private String buildVerificationHtml(String verifyUrl) {
        return """
            <div style="font-family: sans-serif; max-width: 480px; margin: 0 auto; padding: 32px;">
                <h2 style="color: #111;">Verify your email</h2>
                <p style="color: #555; line-height: 1.5;">
                    Thanks for signing up for Railhook. Click the button below to verify your email address.
                </p>
                <a href="%s"
                   style="display: inline-block; padding: 12px 24px; background: #111; color: #fff;
                          text-decoration: none; border-radius: 6px; margin: 16px 0;">
                    Verify Email
                </a>
%s                <p style="color: #999; font-size: 12px; margin-top: 24px;">
                    If you didn't create an account, you can safely ignore this email.
                    This link expires in 24 hours.
                </p>
            </div>
            """.formatted(verifyUrl, linkFallback(verifyUrl));
    }

    private String buildEmailChangeConfirmationHtml(String confirmUrl) {
        return """
            <div style="font-family: sans-serif; max-width: 480px; margin: 0 auto; padding: 32px;">
                <h2 style="color: #111;">Confirm your new email</h2>
                <p style="color: #555; line-height: 1.5;">
                    Someone asked to use this address for a Railhook account. If that was you,
                    confirm it with the button below.
                </p>
                <a href="%s"
                   style="display: inline-block; padding: 12px 24px; background: #111; color: #fff;
                          text-decoration: none; border-radius: 6px; margin: 16px 0;">
                    Confirm new email
                </a>
%s                <p style="color: #999; font-size: 12px; margin-top: 24px;">
                    The link expires in 24 hours. Until then the account keeps its current address.
                    If it wasn't you, ignore this email and nothing changes.
                </p>
            </div>
            """.formatted(confirmUrl, linkFallback(confirmUrl));
    }

    private String buildEmailChangeNoticeHtml(String maskedNew, String cancelUrl, String help) {
        return """
            <div style="font-family: sans-serif; max-width: 480px; margin: 0 auto; padding: 32px;">
                <h2 style="color: #111;">Your email is being changed</h2>
                <p style="color: #555; line-height: 1.5;">
                    Someone asked to change the email address of your Railhook account to
                    <strong>%s</strong>. If that was you, there is nothing to do: the change completes
                    when the new address is confirmed.
                </p>
                <p style="color: #555; line-height: 1.5;">
                    If it wasn't you, cancel it. This also signs out every session.
                </p>
                <a href="%s"
                   style="display: inline-block; padding: 12px 24px; background: #b42318; color: #fff;
                          text-decoration: none; border-radius: 6px; margin: 16px 0;">
                    This wasn't me
                </a>
%s                <p style="color: #999; font-size: 12px; margin-top: 24px;">
                    Then reset your password. If you need help, %s.
                </p>
            </div>
            """.formatted(maskedNew, cancelUrl, linkFallback(cancelUrl), help);
    }
}
