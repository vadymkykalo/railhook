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
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConditionalOnProperty(name = "springdoc.swagger-ui.enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiConfig {

    /** What docker-compose publishes, and what the quickstart tells a reader to open. */
    private static final String DEFAULT_BASE_URL = "http://localhost:8080";

    static {
        // AuthContext is resolved from the bearer token or the API key by
        // AuthContextArgumentResolver — it is never sent by a caller. Left to itself, springdoc
        // reads it as a method parameter and publishes it as a required `auth` query object,
        // which describes an API that does not exist.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthContext.class);
    }

    @Bean
    public OpenAPI webhookPlatformOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Railhook API")
                        .description("""
                                Enterprise-grade distributed webhook delivery system with at-least-once guarantees.
                                
                                ## Features
                                - **Event Ingestion**: Send events via REST API with automatic fan-out to subscribed endpoints
                                - **Reliable Delivery**: Automatic retries with exponential backoff (1m → 24h, 7 attempts)
                                - **HMAC Signatures**: Secure webhook verification with SHA-256 signatures
                                - **Multi-tenant**: Organization-based isolation with JWT authentication
                                - **Rate Limiting**: Distributed rate limiting via Redis
                                
                                ## Authentication
                                - **JWT Bearer Token**: For all operations (UI / user context)
                                - **API Key**: For all project-scoped operations (`X-API-Key` header, SDK usage)
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
                        new Tag().name("API Keys").description("API key management for event ingestion")))
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
                                        + "of tenant org membership — required for cross-tenant admin endpoints")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    /**
     * One server, whose host the reader fills in: Railhook is self-hosted, so there is no address
     * this document could name that would be right for anybody but its author. The default is the
     * local one, which is where a reader following the quickstart already is: the port is the
     * one docker-compose publishes, not whatever this process happens to be bound to — a spec
     * regenerated from a test on a random port would otherwise document that port.
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
