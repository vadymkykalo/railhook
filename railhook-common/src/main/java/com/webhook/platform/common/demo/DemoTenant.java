package com.webhook.platform.common.demo;

import java.util.UUID;

/**
 * The fixed identities of the public demo: one read-only Organization, the one person signed in
 * to it, and its one Project.
 *
 * <p>Fixed rather than generated, and the same on every installation, so that code which never
 * sees a demo session can still recognise demo rows by id alone — the worker's DLQ gauges, which
 * would otherwise page an operator about the demo's own seeded Failed Messages, and the platform
 * overview, which would otherwise count the demo's history as traffic. Whether the demo is
 * enabled does not matter to those callers: on an installation that never seeded it the ids
 * simply match nothing.
 */
public final class DemoTenant {

    public static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-4000-8000-00000000de01");

    public static final UUID USER_ID = UUID.fromString("00000000-0000-4000-8000-00000000de02");

    public static final UUID PROJECT_ID = UUID.fromString("00000000-0000-4000-8000-00000000de03");

    private DemoTenant() {
    }

    public static boolean isDemoOrganization(UUID organizationId) {
        return ORGANIZATION_ID.equals(organizationId);
    }
}
