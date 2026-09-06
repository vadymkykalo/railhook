package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.AlertEvent;
import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.enums.AlertChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import com.webhook.platform.common.http.SsrfProtectionCustomizer;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AlertNotificationService {

    private final WebClient webClient;
    private final EmailService emailService;
    private final boolean enabled;

    public AlertNotificationService(
            WebClient.Builder webClientBuilder,
            EmailService emailService,
            @Value("${app.alerts.notifications-enabled:false}") boolean enabled,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") java.util.List<String> allowedHosts) {
        // Validating the URL when the rule is written is necessary and not sufficient: between
        // that check and this request the name can resolve somewhere else. Every other outbound
        // client in the product closes that window with the same connector; this one did not,
        // and it is the client a user gets to aim.
        this.webClient = webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(
                        SsrfProtectionCustomizer.apply(HttpClient.create(), allowPrivateIps, allowedHosts)))
                .defaultHeader("User-Agent", "Hookflow-Alerts/1.0")
                .build();
        this.emailService = emailService;
        this.enabled = enabled;
    }

    @Async
    public void dispatch(AlertRule rule, AlertEvent event) {
        if (rule.getMuted()) {
            log.debug("Skipping notification for muted rule '{}'", rule.getName());
            return;
        }
        if (rule.getSnoozedUntil() != null && rule.getSnoozedUntil().isAfter(event.getCreatedAt())) {
            log.debug("Skipping notification for snoozed rule '{}'", rule.getName());
            return;
        }

        AlertChannel channel = rule.getChannel();
        if (channel == null || channel == AlertChannel.IN_APP) {
            return;
        }

        if (!enabled) {
            log.info("========== ALERT NOTIFICATION (dry-run) ==========");
            log.info("Channel: {}", channel);
            log.info("Rule: {} ({})", rule.getName(), rule.getAlertType());
            log.info("Severity: {}", event.getSeverity());
            log.info("Title: {}", event.getTitle());
            log.info("Message: {}", event.getMessage());
            log.info("Value: {} / Threshold: {}", event.getCurrentValue(), event.getThresholdValue());
            if (channel == AlertChannel.SLACK || channel == AlertChannel.WEBHOOK) {
                log.info("URL: {}", rule.getWebhookUrl());
            }
            if (channel == AlertChannel.EMAIL) {
                log.info("Recipients: {}", rule.getEmailRecipients());
            }
            log.info("=================================================");
            return;
        }

        try {
            switch (channel) {
                case SLACK -> sendSlack(rule, event);
                case WEBHOOK -> sendWebhook(rule, event);
                case EMAIL -> sendEmail(rule, event);
                default -> log.warn("Unknown alert channel: {}", channel);
            }
        } catch (Exception e) {
            log.error("Failed to send {} notification for rule '{}': {}",
                    channel, rule.getName(), e.getMessage());
        }
    }

    private void sendSlack(AlertRule rule, AlertEvent event) {
        String url = rule.getWebhookUrl();
        if (url == null || url.isBlank()) {
            log.warn("Slack webhook URL is empty for rule '{}'", rule.getName());
            return;
        }

        String color = switch (event.getSeverity()) {
            case CRITICAL -> "#dc2626";
            case WARNING -> "#f59e0b";
            case INFO -> "#3b82f6";
        };

        String valueText = event.getCurrentValue() != null && event.getThresholdValue() != null
                ? String.format("%.1f / %.1f", event.getCurrentValue(), event.getThresholdValue())
                : "—";

        Map<String, Object> payload = Map.of(
                "attachments", List.of(Map.of(
                        "color", color,
                        "fallback", "[" + event.getSeverity() + "] " + event.getTitle(),
                        "blocks", List.of(
                                Map.of(
                                        "type", "section",
                                        "text", Map.of(
                                                "type", "mrkdwn",
                                                "text", "*" + event.getSeverity() + " Alert: " + event.getTitle() + "*"
                                                        + (event.getMessage() != null ? "\n" + event.getMessage() : "")
                                        )
                                ),
                                Map.of(
                                        "type", "section",
                                        "fields", List.of(
                                                Map.of("type", "mrkdwn", "text", "*Rule:*\n" + rule.getName()),
                                                Map.of("type", "mrkdwn", "text", "*Value / Threshold:*\n" + valueText),
                                                Map.of("type", "mrkdwn", "text", "*Type:*\n" + rule.getAlertType()),
                                                Map.of("type", "mrkdwn", "text", "*Window:*\n" + rule.getWindowMinutes() + " min")
                                        )
                                )
                        )
                ))
        );

        webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                .block();

        log.info("Slack notification sent for rule '{}'", rule.getName());
    }

    private void sendWebhook(AlertRule rule, AlertEvent event) {
        String url = rule.getWebhookUrl();
        if (url == null || url.isBlank()) {
            log.warn("Webhook URL is empty for rule '{}'", rule.getName());
            return;
        }

        Map<String, Object> payload = Map.of(
                "event", "alert.fired",
                "rule", Map.of(
                        "id", rule.getId().toString(),
                        "name", rule.getName(),
                        "alertType", rule.getAlertType().name(),
                        "severity", rule.getSeverity().name()
                ),
                "alert", Map.of(
                        "id", event.getId().toString(),
                        "title", event.getTitle(),
                        "message", event.getMessage() != null ? event.getMessage() : "",
                        "currentValue", event.getCurrentValue() != null ? event.getCurrentValue() : 0,
                        "thresholdValue", event.getThresholdValue() != null ? event.getThresholdValue() : 0,
                        "severity", event.getSeverity().name(),
                        "createdAt", event.getCreatedAt().toString()
                ),
                "projectId", rule.getProjectId().toString()
        );

        webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                .block();

        log.info("Webhook notification sent to {} for rule '{}'", url, rule.getName());
    }

    private void sendEmail(AlertRule rule, AlertEvent event) {
        String recipients = rule.getEmailRecipients();
        if (recipients == null || recipients.isBlank()) {
            log.warn("Email recipients empty for rule '{}'", rule.getName());
            return;
        }

        String subject = "[" + event.getSeverity() + "] " + event.getTitle() + " — Hookflow Alert";

        String valueText = event.getCurrentValue() != null && event.getThresholdValue() != null
                ? String.format("%.1f / %.1f", event.getCurrentValue(), event.getThresholdValue())
                : "N/A";

        String html = """
            <div style="font-family: sans-serif; max-width: 520px; margin: 0 auto; padding: 32px;">
                <h2 style="color: #111;">%s Alert: %s</h2>
                <p style="color: #555; line-height: 1.5;">%s</p>
                <table style="width: 100%%; border-collapse: collapse; margin: 16px 0;">
                    <tr><td style="padding: 8px; border-bottom: 1px solid #eee; color: #888;">Rule</td>
                        <td style="padding: 8px; border-bottom: 1px solid #eee; font-weight: 600;">%s</td></tr>
                    <tr><td style="padding: 8px; border-bottom: 1px solid #eee; color: #888;">Type</td>
                        <td style="padding: 8px; border-bottom: 1px solid #eee;">%s</td></tr>
                    <tr><td style="padding: 8px; border-bottom: 1px solid #eee; color: #888;">Value / Threshold</td>
                        <td style="padding: 8px; border-bottom: 1px solid #eee; font-family: monospace;">%s</td></tr>
                    <tr><td style="padding: 8px; color: #888;">Window</td>
                        <td style="padding: 8px;">%d min</td></tr>
                </table>
                <p style="color: #999; font-size: 12px; margin-top: 24px;">
                    This alert was generated by Hookflow. Log in to your dashboard to investigate.
                </p>
            </div>
            """.formatted(
                event.getSeverity(),
                escapeHtml(event.getTitle()),
                event.getMessage() != null ? escapeHtml(event.getMessage()) : "",
                escapeHtml(rule.getName()),
                rule.getAlertType(),
                valueText,
                rule.getWindowMinutes()
        );

        for (String email : recipients.split(",")) {
            String trimmed = email.trim();
            if (!trimmed.isEmpty()) {
                emailService.sendAlertEmail(trimmed, subject, html);
            }
        }

        log.info("Email alert sent to {} for rule '{}'", recipients, rule.getName());
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
