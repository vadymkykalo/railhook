package com.webhook.platform.api.mcp;

import com.webhook.platform.api.dto.EndpointRequest;
import com.webhook.platform.api.dto.EndpointResponse;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.service.EndpointService;
import com.webhook.platform.api.service.billing.QuotaType;
import com.webhook.platform.api.service.billing.RequireQuota;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The plan quotas the REST controllers declare with {@link RequireQuota}, declared again for the
 * MCP tools that create the same things.
 *
 * <p>A separate bean because the annotation is an aspect: it applies to a call through a Spring
 * proxy, and the tool methods are invoked reflectively on the bean Spring AI scanned. The
 * {@code auth} and {@code projectId} parameter names are what the aspect reads the organization
 * and project from, so neither may be renamed.
 */
@Component
public class McpQuotaGuard {

    private final EndpointService endpointService;

    public McpQuotaGuard(EndpointService endpointService) {
        this.endpointService = endpointService;
    }

    @RequireQuota(QuotaType.ENDPOINTS_PER_PROJECT)
    public EndpointResponse createEndpoint(AuthContext auth, UUID projectId, EndpointRequest request) {
        auth.validateProjectAccess(projectId);
        return endpointService.createEndpoint(projectId, request);
    }
}
