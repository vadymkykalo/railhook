package com.webhook.platform.api.security;

/**
 * What a handler requires of the caller's identity, independent of which credential they
 * presented.
 *
 * <p>Deliberately not "the minimum {@link com.webhook.platform.api.domain.enums.MembershipRole}":
 * the four roles are not a line. {@code OWNER}, {@code DEVELOPER} and {@code VIEWER} order
 * naturally, but {@code API_KEY} sits outside that order entirely — a key is neither above nor
 * below a Viewer, it is a different kind of caller whose permissions come from its scope. An
 * annotation phrased as a minimum role would have had to invent a position for it.
 *
 * <p>These three levels are exactly what the handlers already expressed by hand, so the
 * annotation says the same thing the imperative call did, in a place a reviewer reads.
 */
public enum AccessLevel {

    /**
     * Any authenticated caller. Declared rather than omitted, so "this handler needs nothing"
     * is a statement someone made rather than a line someone forgot.
     */
    READ,

    /**
     * Rejects a Viewer, and rejects an API key whose scope is READ_ONLY. Owners, Developers
     * and READ_WRITE keys pass. Matches {@code RbacUtil.requireWriteAccess}.
     */
    WRITE,

    /**
     * Owners only. An API key never holds OWNER, so this also excludes every key regardless of
     * scope. Matches {@code RbacUtil.requireOwnerAccess}.
     */
    OWNER
}
