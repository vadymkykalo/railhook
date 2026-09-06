package com.webhook.platform.common.enums;

/**
 * Which third party a Source receives webhooks from.
 *
 * <p>Every name but {@code GENERIC} has a verifier written for it, so a source naming one can be
 * put in {@code PROVIDER} mode and Railhook knows how that vendor signs. {@code GENERIC} is the
 * label for a provider Railhook has no preset for — it is verified in {@code HMAC_GENERIC} mode,
 * with the header and prefix that provider signs in, and {@code WebhookVerifierFactory} answers
 * "no built-in verifier" for it deliberately rather than by omission.
 *
 * <p>There was a {@code CUSTOM} here too. It meant exactly what {@code GENERIC} means, had no
 * verifier and no label of its own anywhere in the product, so the only thing choosing it could
 * change was the word on the source's badge — two names for one idea, one of which nothing
 * explained. V063 moves the sources that had it to {@code GENERIC}.
 */
public enum ProviderType {
    GENERIC,
    GITHUB,
    GITLAB,
    STRIPE,
    SHOPIFY,
    SLACK,
    TWILIO
}
