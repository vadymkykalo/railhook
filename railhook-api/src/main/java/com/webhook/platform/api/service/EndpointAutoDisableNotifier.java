package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.enums.AlertSeverity;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Separate from the sweep, which spans organizations, because each notice is written in its own tenant. */
@Component
@Slf4j
public class EndpointAutoDisableNotifier {

    private final AlertService alertService;
    private final EmailService emailService;
    private final MembershipRepository membershipRepository;
    private final String baseUrl;

    public EndpointAutoDisableNotifier(
            AlertService alertService,
            EmailService emailService,
            MembershipRepository membershipRepository,
            @Value("${app.base-url:http://localhost:5173}") String baseUrl) {
        this.alertService = alertService;
        this.emailService = emailService;
        this.membershipRepository = membershipRepository;
        this.baseUrl = baseUrl;
    }

    public void endpointDisabled(Endpoint endpoint) {
        String title = "Endpoint disabled after continuous failure";
        String message = endpoint.getUrl() + " has not accepted a delivery since "
                + endpoint.getFailingSince() + ". Railhook has stopped sending to it. "
                + "Anything that was queued is in Failed Messages, and re-enabling the endpoint "
                + "lets you retry it.";

        TenantContext.runAs(endpoint.getOrganizationId(), () -> {
            alertService.raiseSystemAlert(endpoint.getProjectId(), endpoint.getId(),
                    AlertSeverity.CRITICAL, title, message);
            mailOwners(endpoint.getOrganizationId(), title, endpoint.getUrl(),
                    endpoint.getFailingSince(), endpoint.getConsecutiveFailures(),
                    endpointLink(endpoint.getProjectId()));
        });
    }

    /** projectId comes from the Destination's Source; a Destination has no project of its own. */
    public void destinationDisabled(IncomingDestination destination, UUID projectId) {
        String title = "Destination disabled after continuous failure";
        String message = destination.getUrl() + " has not accepted a forward since "
                + destination.getFailingSince() + ". Railhook has stopped forwarding to it. "
                + "Anything that was queued is in Failed Messages, and re-enabling the destination "
                + "lets you retry it.";

        TenantContext.runAs(destination.getOrganizationId(), () -> {
            // endpointId stays null, or the dashboard would link to an endpoint that does not exist.
            alertService.raiseSystemAlert(projectId, null, AlertSeverity.CRITICAL, title, message);
            mailOwners(destination.getOrganizationId(), title, destination.getUrl(),
                    destination.getFailingSince(), destination.getConsecutiveFailures(),
                    destinationLink(projectId));
        });
    }

    private void mailOwners(UUID organizationId, String subject, String url, Instant failingSince,
            Integer failures, String link) {
        if (!emailService.isEnabled()) {
            return;
        }
        List<String> owners = membershipRepository.findOwnerEmails(organizationId);
        if (owners.isEmpty()) {
            log.warn("Organization {} has no verified owner to tell about {}", organizationId, url);
            return;
        }
        String html = body(url, failingSince, failures, link);
        for (String owner : owners) {
            emailService.sendAlertEmail(owner, "[Railhook] " + subject, html);
        }
    }

    private String endpointLink(UUID projectId) {
        return baseUrl + "/projects/" + projectId + "/endpoints";
    }

    private String destinationLink(UUID projectId) {
        return baseUrl + "/projects/" + projectId + "/incoming-sources";
    }

    private String body(String url, Instant failingSince, Integer failures, String link) {
        return """
            <div style="font-family: sans-serif; max-width: 520px; margin: 0 auto; padding: 32px;">
                <h2 style="color: #111;">Railhook stopped sending to one of your targets</h2>
                <p style="color: #555; line-height: 1.5;">
                    Every attempt has failed since the time below, so Railhook turned it off rather
                    than keep retrying. This is not a limit you hit — it is a receiver that has
                    been unreachable or answering errors for days.
                </p>
                <table style="width: 100%%; border-collapse: collapse; margin: 16px 0;">
                    <tr><td style="padding: 8px; border-bottom: 1px solid #eee; color: #888;">Target</td>
                        <td style="padding: 8px; border-bottom: 1px solid #eee; font-family: monospace;">%s</td></tr>
                    <tr><td style="padding: 8px; border-bottom: 1px solid #eee; color: #888;">Failing since</td>
                        <td style="padding: 8px; border-bottom: 1px solid #eee;">%s</td></tr>
                    <tr><td style="padding: 8px; color: #888;">Consecutive failures</td>
                        <td style="padding: 8px;">%d</td></tr>
                </table>
                <p style="color: #555; line-height: 1.5;">
                    Fix the receiver, then turn it back on — that clears the record of failure and
                    starts delivery again. Nothing was lost: what was queued is waiting in
                    <strong>Failed Messages</strong>, where you can retry it.
                </p>
                <p><a href="%s" style="color: #1D4BFF;">Open it in Railhook</a></p>
            </div>
            """.formatted(escapeHtml(url), failingSince, failures == null ? 0 : failures,
                escapeHtml(link));
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
