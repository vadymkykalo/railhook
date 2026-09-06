package com.webhook.platform.common.enums;

/**
 * Which signature headers an Endpoint receives.
 *
 * <p>Two schemes, because they answer different needs. Ours — {@code X-Signature: t=…,v1=…}
 * over {@code <timestamp>.<body>} — is what every existing receiver was built against.
 * <a href="https://github.com/standard-webhooks/standard-webhooks">Standard Webhooks</a> —
 * {@code webhook-id} / {@code webhook-timestamp} / {@code webhook-signature} over
 * {@code <id>.<timestamp>.<body>} — is what an off-the-shelf verification library
 * understands, which is the difference between a receiver reading our documentation and a
 * receiver adding one dependency.</p>
 */
public enum SignatureScheme {

    /** Only {@code X-Signature}. For a receiver that must not see unexpected headers. */
    LEGACY,

    /** Only the Standard Webhooks headers. Breaks a receiver verifying {@code X-Signature}. */
    STANDARD,

    /**
     * Both, and the default.
     *
     * <p>Extra headers cost a receiver nothing — it verifies the one it knows and ignores the
     * rest — so sending both means an existing endpoint keeps working untouched while a new
     * one can use a standard library from the start. Nobody has to migrate, and nobody is
     * asked to choose before they know the difference.</p>
     */
    BOTH
}
