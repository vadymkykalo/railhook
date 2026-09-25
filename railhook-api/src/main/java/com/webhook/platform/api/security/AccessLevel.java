package com.webhook.platform.api.security;

/**
 * Not a minimum membership role: an API key is neither above nor below a Viewer, its
 * permissions come from its scope.
 */
public enum AccessLevel {

    READ,

    /** Rejects a Viewer and a READ_ONLY API key. */
    WRITE,

    /** Owners only, so it also excludes every API key. */
    OWNER
}
