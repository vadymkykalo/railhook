package com.webhook.platform.api.service.captcha;

/** A real bean, not a null check, so ProductionSafetyValidator can refuse it on a hosted deployment. */
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
