package com.webhook.platform.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;
import io.swagger.v3.oas.models.servers.ServerVariables;
import io.swagger.v3.oas.models.tags.Tag;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.PortalContext;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConditionalOnProperty(name = "springdoc.swagger-ui.enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiConfig {

    private static final String DEFAULT_BASE_URL = "http://localhost:8080";

    static {
        // Resolved from the credential, never sent by a caller. Otherwise springdoc documents
        // them as required query objects.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthContext.class);
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(PortalContext.class);
    }

    @Bean
    public OpenAPI webhookPlatformOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Railhook API")
                        .description("""
                                Send webhooks to the endpoints your customers register, and receive webhooks from \
                                any provider, with every attempt on record.

                                ## What you can do
                                - **Outgoing**: post an event once; Railhook delivers it, signed, to every subscribed endpoint \
                                and retries on its own (1m → 24h, 7 attempts). What never arrives lands in Failed Messages.
                                - **Incoming**: give each provider its own ingress URL; Railhook verifies the signature and \
                                forwards the event to your destinations.
                                - **Replay**: send past events again with Time Machine.

                                ## Authentication
                                - **API key** (`X-API-Key` header): project-scoped calls, including sending events. What the SDKs use.
                                - **Bearer token**: account and organization calls made on behalf of a signed-in user.
                                - **Portal session** (`Authorization: Bearer rhp_…`): the `/api/v1/portal/**` calls the \
                                embedded customer portal makes for one Consumer. Opened by your backend with an API key.

                                Guides: [/docs/](/docs/)
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Railhook")
                                .url("https://github.com/vadymkykalo/railhook"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(installationServer()))
                .tags(List.of(
                        new Tag().name("Authentication").description("User registration, login, and session management"),
                        new Tag().name("Organizations").description("Organization and member management"),
                        new Tag().name("Projects").description("Project management and dashboard statistics"),
                        new Tag().name("Endpoints").description("Webhook endpoint configuration"),
                        new Tag().name("Subscriptions").description("Event type subscriptions for endpoints"),
                        new Tag().name("Events").description("Event ingestion and history"),
                        new Tag().name("Deliveries").description("Delivery status, attempts, and replay operations"),
                        new Tag().name("API Keys").description("API key management for event ingestion"),
                        new Tag().name("Consumers").description("Your own users, their endpoints, and the portal sessions you open for them"),
                        new Tag().name("Portal").description("What the embedded customer portal calls, authenticated by a portal session")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT token obtained from /api/v1/auth/login"))
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")
                                .description("Project API key for event ingestion"))
                        .addSecuritySchemes("platformAdminToken", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Platform-Admin-Token")
                                .description("Cluster-operator credential (PLATFORM_ADMIN_TOKEN env var), independent "
                                        + "of tenant org membership — required for cross-tenant admin endpoints"))
                        .addSecuritySchemes("portalSession", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("rhp_…")
                                .description("Portal session token, returned once by "
                                        + "POST /api/v1/projects/{projectId}/consumers/{consumerId}/portal-sessions")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    /**
     * A fixed default rather than the bound port, so a spec regenerated from a test on a random
     * port stays the same.
     */
    private Server installationServer() {
        return new Server()
                .url("{baseUrl}")
                .description("Your Railhook installation — the same origin the dashboard runs on")
                .variables(new ServerVariables().addServerVariable("baseUrl", new ServerVariable()
                        ._default(DEFAULT_BASE_URL)
                        .description("Scheme and host of your installation, without a trailing slash")));
    }
}
