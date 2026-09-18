package com.webhook.platform.api.security;

import java.util.UUID;

/**
 * Who a portal request is for: one Consumer of one project. The organization is not here —
 * {@code TenantContextFilter} has already confined every query to it.
 *
 * <p>Resolved by {@link PortalContextArgumentResolver} from the portal session's authentication;
 * a portal handler declares it as a parameter, as a tenant handler declares {@code AuthContext}.
 */
public record PortalContext(UUID sessionId, UUID projectId, UUID consumerId) {
}
