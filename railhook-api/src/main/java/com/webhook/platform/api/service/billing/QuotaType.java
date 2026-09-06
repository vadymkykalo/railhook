package com.webhook.platform.api.service.billing;

/**
 * Declarative quota types for use with {@link RequireQuota} annotation.
 * Each type maps to a specific limit in the Plan entity.
 */
public enum QuotaType {

    /** Monthly event ingestion limit (org-scoped). */
    EVENTS_PER_MONTH,

    /** Max endpoints per project (project-scoped, requires projectId). */
    ENDPOINTS_PER_PROJECT,

    /** Max projects per organization (org-scoped). */
    PROJECTS,

    /** Max members per organization (org-scoped). */
    MEMBERS,

    /** Max active tunnels per organization (org-scoped, includes feature gate). */
    TUNNELS
}
