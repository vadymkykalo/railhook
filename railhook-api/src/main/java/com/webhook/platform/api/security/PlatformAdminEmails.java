package com.webhook.platform.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Necessary, never sufficient: access also needs a verified address, an active account and a
 * recent sign-in. Matching is exact, so {@code ops@example.com} never matches
 * {@code xops@example.com}. Empty means nobody.
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
