package com.webhook.platform.api.service.billing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative quota enforcement via AOP.
 * Place on controller or service methods to check plan quotas before execution.
 *
 * <p>The aspect resolves {@code organizationId} from {@link com.webhook.platform.api.security.AuthContext}
 * in method args. For project-scoped quotas (e.g. ENDPOINTS_PER_PROJECT), it also resolves
 * {@code projectId} from {@code @PathVariable("projectId")} or parameter named "projectId".</p>
 *
 * <p>When {@code billing.enabled=false}, all checks are skipped (self-hosted mode).</p>
 *
 * <pre>
 * &#64;RequireQuota(QuotaType.ENDPOINTS_PER_PROJECT)
 * public EndpointResponse createEndpoint(@PathVariable UUID projectId, ..., AuthContext auth) { }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireQuota {
    QuotaType value();
}
