package com.webhook.platform.api.service.signin;

/**
 * A person an identity provider has vouched for: a stable subject, and an address the provider
 * has itself verified.
 *
 * @param subject      the provider's permanent id for the person; unlike the address, it never changes
 * @param hostedDomain the Google Workspace domain the account belongs to, absent for a personal account
 */
public record VerifiedIdentity(String provider, String subject, String email, String fullName, String givenName,
                               String hostedDomain) {
}
