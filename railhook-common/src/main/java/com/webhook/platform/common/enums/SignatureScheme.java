package com.webhook.platform.common.enums;

/**
 * {@code X-Signature} is what existing receivers verify. Standard Webhooks headers work with
 * off-the-shelf libraries.
 */
public enum SignatureScheme {

    /** Only {@code X-Signature}. For a receiver that must not see unexpected headers. */
    LEGACY,

    /** Only the Standard Webhooks headers. Breaks a receiver verifying {@code X-Signature}. */
    STANDARD,

    /** Default. Receivers ignore headers they do not know, so sending both breaks nobody. */
    BOTH
}
