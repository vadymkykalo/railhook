package com.webhook.platform.common.demo;

import java.util.UUID;

/**
 * Fixed ids, the same on every installation, so code without a demo session (DLQ gauges, the
 * platform overview) can still exclude demo rows. Where the demo was never seeded they match
 * nothing.
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
