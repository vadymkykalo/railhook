package com.webhook.platform.api.service.billing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative feature-flag enforcement via AOP.
 * Place on controller or service methods to check if the org's plan includes the feature.
 *
 * <p>The aspect resolves {@code organizationId} from {@link com.webhook.platform.api.security.AuthContext}
 * in method args.</p>
 *
 * <p>When {@code billing.enabled=false}, all features are enabled (self-hosted mode).</p>
 *
 * <pre>
 * &#64;RequireFeature("workflows")
 * public WorkflowResponse createWorkflow(..., AuthContext auth) { }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireFeature {
    /** Feature name as stored in plans.features JSONB (e.g. "workflows", "rules", "mTLS", "sso"). */
    String value();
}
