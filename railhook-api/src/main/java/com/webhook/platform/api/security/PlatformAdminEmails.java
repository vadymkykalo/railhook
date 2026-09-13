package com.webhook.platform.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The addresses of the people who run this deployment ({@code PLATFORM_ADMIN_EMAILS}).
 *
 * <p>Membership in this list is necessary and never sufficient: {@link
 * com.webhook.platform.api.service.PlatformAdminAccessService} also demands a verified address,
 * an active account and a recent sign-in. Kept as its own type so "is this address listed" has
 * one answer — exact, case-insensitive, whitespace-trimmed — and nothing does a {@code contains}
 * on the raw string, where {@code ops@example.com} would match {@code xops@example.com}.
 *
 * <p>Empty by default, and empty means nobody: a self-hosted install gets no panel until its
 * operator names themselves.
 */
@Component
public class PlatformAdminEmails {

    private final Set<String> addresses;

    public PlatformAdminEmails(@Value("${platform.admin.emails:}") String configured) {
        this.addresses = configured == null ? Set.of() : Arrays.stream(configured.split(","))
                .map(PlatformAdminEmails::normalize)
                .filter(address -> !address.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isListed(String email) {
        return email != null && addresses.contains(normalize(email));
    }

    public boolean isEmpty() {
        return addresses.isEmpty();
    }

    public int size() {
        return addresses.size();
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
