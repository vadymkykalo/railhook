package com.webhook.platform.api.service.captcha;

/** Defaults to accepting everything: no third party unless an operator configures one. */
public interface CaptchaVerifier {

    /**
     * @return true when the challenge is satisfied, or when no challenge is configured
     */
    boolean verify(String token, String clientIp);

    boolean isEnabled();
}
