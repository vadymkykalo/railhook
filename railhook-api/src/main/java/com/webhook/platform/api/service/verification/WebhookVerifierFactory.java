package com.webhook.platform.api.service.verification;

import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Factory that returns the appropriate verification strategy based on
 * the source's verification mode and provider type.
 */
@Component
public class WebhookVerifierFactory {

    private final String ingressBaseUrl;

    public WebhookVerifierFactory(@Value("${webhook.ingress-base-url:}") String ingressBaseUrl) {
        this.ingressBaseUrl = ingressBaseUrl;
    }

    /**
     * Returns a verification strategy for the given source, or null if verification is disabled.
     */
    public WebhookVerificationStrategy getVerifier(IncomingSource source) {
        if (source.getVerificationMode() == VerificationMode.NONE) {
            return null;
        }

        if (source.getVerificationMode() == VerificationMode.HMAC_GENERIC) {
            return new GenericHmacVerifier(
                    source.getHmacHeaderName(),
                    source.getHmacSignaturePrefix());
        }

        // PROVIDER mode — pick strategy based on providerType
        if (source.getVerificationMode() == VerificationMode.PROVIDER) {
            WebhookVerificationStrategy verifier = getProviderVerifier(source.getProviderType());
            if (verifier == null) {
                throw new IllegalStateException(
                        "No verifier available for provider type: " + source.getProviderType()
                                + " on source " + source.getId());
            }
            return verifier;
        }

        throw new IllegalStateException(
                "Unknown verification mode: " + source.getVerificationMode()
                        + " on source " + source.getId());
    }

    /**
     * Whether {@code PROVIDER} mode can actually verify this provider.
     *
     * <p>{@code GENERIC} is the one name that answers no on purpose: it is the label for a
     * provider Railhook has no preset for, and the way to verify one of those is
     * {@code HMAC_GENERIC} with the header and prefix that provider signs in. Selecting it with
     * {@code PROVIDER} mode used to save happily and then throw {@link IllegalStateException} at
     * ingress, so the source looked configured and the failure arrived once the provider was
     * already sending. {@code IncomingSourceService} asks this at write time instead; the two
     * cannot disagree because the answer comes from the same switch that builds the verifier.
     */
    public boolean supportsProviderVerification(ProviderType providerType) {
        return getProviderVerifier(providerType) != null;
    }

    private WebhookVerificationStrategy getProviderVerifier(ProviderType providerType) {
        if (providerType == null) {
            return null;
        }
        // Exhaustive on purpose — no `default` arm. A new ProviderType is then a compile error
        // here rather than a source that saves in PROVIDER mode and cannot verify a thing.
        return switch (providerType) {
            case GITHUB -> new GitHubVerifier();
            // Not GitHubVerifier: GitLab sends a plain shared token in X-Gitlab-Token and
            // never sends X-Hub-Signature-256, so routing it here failed every delivery.
            case GITLAB -> new GitLabVerifier();
            case STRIPE -> new StripeVerifier();
            case SLACK -> new SlackVerifier();
            case SHOPIFY -> new ShopifyVerifier();
            case TWILIO -> new TwilioVerifier(ingressBaseUrl);
            // Like Twilio, Square signs the URL it was configured with, so this one needs the
            // ingress base too rather than whatever Host the request arrives claiming.
            case SQUARE -> new SquareVerifier(ingressBaseUrl);
            case ADYEN -> new AdyenVerifier();
            // Not an HMAC at all: the stored value is SendGrid's public verification key, and
            // nothing about holding it lets anyone forge a webhook.
            case SENDGRID -> new SendGridVerifier();
            // HubSpot v3 signs the method and the full URL as well as the body, so this one also
            // needs the ingress base rather than the request's own idea of its host.
            case HUBSPOT -> new HubSpotVerifier(ingressBaseUrl);
            case GENERIC -> null;
        };
    }
}
