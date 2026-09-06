package com.webhook.platform.api.domain.enums;

/**
 * What kind of thing is holding a session.
 *
 * <p>Only two, and they exist because the answer changes what a person does about a session they
 * do not recognise. A browser sign-in is expected and disposable; a {@link #CLI} grant came from
 * the device-code flow, lives on a developer machine, and is the one most likely to still be
 * valid long after the machine stopped being theirs.
 */
public enum SessionClient {

    /** A browser sign-in through {@code /api/v1/auth/login} or registration. */
    WEB,

    /** A device-code grant issued to the CLI through {@code /api/v1/auth/device/token}. */
    CLI
}
