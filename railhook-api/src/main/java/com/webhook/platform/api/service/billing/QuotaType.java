package com.webhook.platform.api.service.billing;

public enum QuotaType {

    /** Requires a projectId. */
    ENDPOINTS_PER_PROJECT,
    PROJECTS,
    MEMBERS,
    /** Also checks the tunnel feature gate. */
    TUNNELS
}
