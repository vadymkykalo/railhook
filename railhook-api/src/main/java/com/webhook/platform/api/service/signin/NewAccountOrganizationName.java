package com.webhook.platform.api.service.signin;

import java.util.Locale;

public final class NewAccountOrganizationName {

    public static final int MAX_LENGTH = 100;

    private NewAccountOrganizationName() {
    }

    public static String of(VerifiedIdentity identity) {
        String domainLabel = firstLabel(identity.hostedDomain());
        String name = domainLabel != null
                ? Character.toUpperCase(domainLabel.charAt(0)) + domainLabel.substring(1)
                : person(identity) + "'s workspace";
        return name.length() <= MAX_LENGTH ? name : name.substring(0, MAX_LENGTH);
    }

    private static String firstLabel(String hostedDomain) {
        if (hostedDomain == null || hostedDomain.isBlank()) {
            return null;
        }
        String label = hostedDomain.trim().toLowerCase(Locale.ROOT).split("\\.")[0];
        return label.isEmpty() ? null : label;
    }

    private static String person(VerifiedIdentity identity) {
        if (identity.givenName() != null && !identity.givenName().isBlank()) {
            return identity.givenName().trim();
        }
        String email = identity.email();
        int at = email == null ? -1 : email.indexOf('@');
        return at > 0 ? email.substring(0, at) : "My";
    }
}
