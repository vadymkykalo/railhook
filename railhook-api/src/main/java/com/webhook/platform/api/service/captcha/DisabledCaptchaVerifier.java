package com.webhook.platform.api.service.captcha;

/**
 * What runs when no CAPTCHA is configured: everything passes.
 *
 * <p>The shipped default, and correct for self-hosting — a deployment whose registration page is
 * on someone's own network has nobody to challenge, and sending its visitors to a third party to
 * prove otherwise would be a worse default than accepting them.
 *
 * <p>It is a real bean rather than a null check at the call site so that the registration path
 * reads the same either way, and so {@code ProductionSafetyValidator} can refuse to start a
 * hosted deployment that left this in place.
 */
public class DisabledCaptchaVerifier implements CaptchaVerifier {

    @Override
    public boolean verify(String token, String clientIp) {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
