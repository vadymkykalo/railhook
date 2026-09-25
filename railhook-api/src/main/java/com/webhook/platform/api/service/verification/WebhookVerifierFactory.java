package com.webhook.platform.api.service.verification;

import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebhookVerifierFactory {

    private final String ingressBaseUrl;

    public WebhookVerifierFactory(@Value("${webhook.ingress-base-url:}") String ingressBaseUrl) {
        this.ingressBaseUrl = ingressBaseUrl;
    }

    /** Null when verification is disabled. */
    public WebhookVerificationStrategy getVerifier(IncomingSource source) {
        if (source.getVerificationMode() == VerificationMode.NONE) {
            return null;
        }

        if (source.getVerificationMode() == VerificationMode.HMAC_GENERIC) {
            return new GenericHmacVerifier(
                    source.getHmacHeaderName(),
                    source.getHmacSignaturePrefix());
        }

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

    // GENERIC answers no: saving it in PROVIDER mode used to throw at ingress once traffic arrived.
    public boolean supportsProviderVerification(ProviderType providerType) {
        return getProviderVerifier(providerType) != null;
    }

    private WebhookVerificationStrategy getProviderVerifier(ProviderType providerType) {
        if (providerType == null) {
            return null;
        }
        // No default arm, so a new ProviderType is a compile error here instead of a source that cannot verify.
        return switch (providerType) {
            case GITHUB -> new GitHubVerifier();
            // GitLab sends a plain token, never X-Hub-Signature-256.
            case GITLAB -> new GitLabVerifier();
            case STRIPE -> new StripeVerifier();
            case SLACK -> new SlackVerifier();
            case SHOPIFY -> new ShopifyVerifier();
            case TWILIO -> new TwilioVerifier(ingressBaseUrl);
            // Square signs the configured URL, so it needs the ingress base, not the request's Host.
            case SQUARE -> new SquareVerifier(ingressBaseUrl);
            case ADYEN -> new AdyenVerifier();
            // The stored value is SendGrid's public key, not a secret.
            case SENDGRID -> new SendGridVerifier();
            // HubSpot v3 signs the method and full URL too, so it needs the ingress base.
            case HUBSPOT -> new HubSpotVerifier(ingressBaseUrl);
            case GENERIC -> null;
        };
    }
}
