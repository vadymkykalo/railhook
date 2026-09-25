package com.webhook.platform.api.service.signin;

/**
 * @param subject the provider's permanent id; unlike the email address it never changes
 */
public record VerifiedIdentity(String provider, String subject, String email, String fullName, String givenName,
                               String hostedDomain) {
}
