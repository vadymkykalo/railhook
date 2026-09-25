package com.webhook.platform.common.enums;

/**
 * Every value except {@code GENERIC} has a built-in verifier. {@code GENERIC} is for providers
 * with no preset and is verified in {@code HMAC_GENERIC} mode.
 */
public enum ProviderType {
    GENERIC,
    GITHUB,
    GITLAB,
    STRIPE,
    SHOPIFY,
    SLACK,
    TWILIO,
    SQUARE,
    ADYEN,
    SENDGRID,
    HUBSPOT
}
