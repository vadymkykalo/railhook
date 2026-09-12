package com.webhook.platform.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.email.from:noreply@example.com}")
    private String fromAddress;

    @Value("${app.base-url:http://localhost:5173}")
    private String baseUrl;

    @Value("${app.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${app.env:development}")
    private String appEnv;

    public EmailService(@Autowired(required = false) JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Whether anything sent through this service can actually reach a person.
     * When false every send below degrades to a log line, so callers that
     * depend on the recipient receiving something — email verification, most
     * of all — must take a different path rather than wait for a reply that
     * is never coming.
     */
    public boolean isEnabled() {
        return emailEnabled;
    }

    /**
     * Whether a short-lived, single-use link may stand in for the mail that could not be sent.
     *
     * <p>On a workstation it must: with {@code app.email.enabled=false} — the shipped default —
     * the log is the only place a password reset can be completed from, and
     * {@link #sendTemporaryPasswordEmail} sets out why a token that expires in an hour and burns
     * on first use is a reasonable thing to print there.
     *
     * <p>Neither half of that reasoning survives a move to production. The expiry does not slow
     * down somebody already reading the log, and single-use means they get there first. So the
     * affordance stays exactly what it was meant to be, and stops at the environment boundary.
     */
    private boolean mayLogLinkInstead() {
        return !"production".equalsIgnoreCase(appEnv);
    }

    private void explainWithheldLink(String what, String to) {
        log.warn("{} for {} was not sent and will not be logged: APP_ENV=production with "
                + "EMAIL_ENABLED=false. Configure SMTP, or the link cannot reach anyone.", what, to);
    }

    public void sendVerificationEmail(String to, String token) {
        String verifyUrl = baseUrl + "/verify-email?token=" + token;

        if (!emailEnabled) {
            if (!mayLogLinkInstead()) {
                explainWithheldLink("An email verification link", to);
                return;
            }
            log.info("========== EMAIL VERIFICATION ==========");
            log.info("To: {}", to);
            log.info("Verify URL: {}", verifyUrl);
            log.info("=========================================");
            return;
        }

        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject("Verify your email — Railhook");
            helper.setText(buildVerificationHtml(verifyUrl), true);
            mailSender.send(message);
            log.info("Verification email sent to {}", to);
        } catch (Exception e) {
            // No fallback to the log. This branch only runs with app.email.enabled=true — a
            // deployment that configured SMTP and had it blink. It did not ask for links in its
            // log, and one refused relay is not a reason to put one there.
            log.error("Failed to send verification email to {}: {}", to, e.getMessage());
        }
    }

    public void sendPasswordResetEmail(String to, String token) {
        String resetUrl = baseUrl + "/reset-password?token=" + token;

        if (!emailEnabled) {
            if (!mayLogLinkInstead()) {
                explainWithheldLink("A password reset link", to);
                return;
            }
            log.info("========== PASSWORD RESET ==========");
            log.info("To: {}", to);
            log.info("Reset URL: {}", resetUrl);
            log.info("====================================");
            return;
        }

        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject("Reset your password — Railhook");
            helper.setText(buildPasswordResetHtml(resetUrl), true);
            mailSender.send(message);
            log.info("Password reset email sent to {}", to);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", to, e.getMessage());
        }
    }

    /**
     * The address an invitee opens to accept, built here because this is where the
     * deployment's base URL lives. Handed back to the inviting owner as well as put in
     * the mail: with {@code app.email.enabled=false} — the shipped default — nothing is
     * sent, and a link the owner can pass on by hand is the only way the invite arrives.
     */
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
            log.info("========== MEMBER INVITE ==========");
            log.info("To: {}", to);
            log.info("Invite URL: {}", inviteUrl);
            log.info("====================================");
            return;
        }

        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject("You've been invited to join an organization — Railhook");
            helper.setText(buildInviteHtml(inviteUrl), true);
            mailSender.send(message);
            log.info("Invite email sent to {}", to);
        } catch (Exception e) {
            // inviteUrl() hands the same link back to the inviting owner, so a copy here buys
            // nothing and leaves it in the least controlled place there is.
            log.error("Failed to send invite email to {}: {}", to, e.getMessage());
        }
    }

    /**
     * Sends the one-time temporary password generated for a brand-new user created by
     * an org invite (see MembershipService#addMember). Unlike the other send*Email
     * methods, this NEVER falls back to logging the secret when app.email.enabled is
     * false: a reset/verification token is short-lived and single-use, but this
     * password grants full, non-expiring account access until changed. In local dev
     * without SMTP configured, use POST /api/v1/auth/forgot-password instead — that
     * flow's token is safe to log because it expires in an hour and is single-use.
     */
    public void sendTemporaryPasswordEmail(String to, String tempPassword) {
        if (!emailEnabled) {
            log.info("========== TEMP PASSWORD EMAIL SKIPPED (app.email.enabled=false) ==========");
            log.info("To: {} — temporary password was generated but NOT logged or emailed.", to);
            log.info("Use POST /api/v1/auth/forgot-password to issue a usable (loggable) reset token instead.");
            log.info("=============================================================================");
            return;
        }

        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject("Your temporary password — Railhook");
            helper.setText(buildTemporaryPasswordHtml(tempPassword), true);
            mailSender.send(message);
            log.info("Temporary password email sent to {}", to);
        } catch (Exception e) {
            // Do not fall back to logging the password on send failure either.
            log.error("Failed to send temporary password email to {}: {}", to, e.getMessage());
        }
    }

    public void sendAlertEmail(String to, String subject, String htmlBody) {
        if (!emailEnabled) {
            log.info("========== ALERT EMAIL ==========");
            log.info("To: {}", to);
            log.info("Subject: {}", subject);
            log.info("=================================");
            return;
        }

        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("Alert email sent to {}", to);
        } catch (Exception e) {
            log.error("Failed to send alert email to {}: {}", to, e.getMessage());
        }
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
                <p style="color: #999; font-size: 12px; margin-top: 24px;">
                    This invitation expires in 48 hours. If you didn't expect this, you can safely ignore it.
                </p>
            </div>
            """.formatted(inviteUrl);
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
                <p style="color: #999; font-size: 12px; margin-top: 24px;">
                    If you didn't request a password reset, you can safely ignore this email.
                    This link expires in 1 hour.
                </p>
            </div>
            """.formatted(resetUrl);
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
                <p style="color: #999; font-size: 12px; margin-top: 24px;">
                    If you didn't create an account, you can safely ignore this email.
                    This link expires in 24 hours.
                </p>
            </div>
            """.formatted(verifyUrl);
    }
}
