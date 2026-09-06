package com.webhook.platform.api.service.captcha;

/**
 * Whether a challenge response is genuine.
 *
 * <p>An interface with two implementations for the same reason {@code BillingProvider} has
 * three: the thing behind it is a third-party service a deployment may or may not use, and the
 * self-hosted default has to be "no third party at all". {@link DisabledCaptchaVerifier} is what
 * runs unless an operator configures otherwise, and it accepts everything — the honest shape of
 * a control that is switched off, rather than a stub that pretends to check.
 */
public interface CaptchaVerifier {

    /**
     * @param token    what the widget produced, or null when the client sent none
     * @param clientIp the caller's address, which every provider takes and uses to score
     * @return true when the challenge is satisfied, or when no challenge is configured
     */
    boolean verify(String token, String clientIp);

    /** Whether this deployment actually challenges anyone. Reported by the readiness check. */
    boolean isEnabled();
}
