package com.webhook.platform.api.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.exception.QuotaExceededException;
import com.webhook.platform.api.exception.UnauthorizedException;
import com.webhook.platform.api.mcp.oauth.McpOAuthAuthenticationToken;
import com.webhook.platform.api.security.ApiKeyAuthenticationToken;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.SuspensionCheck;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.security.UrlValidator;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs one MCP tool call as the API key — or the OAuth grant, which is the same (project, scope)
 * pair — that made it, and turns whatever happens into a result the model can read.
 *
 * <p>Everything a REST request gets from the web layer has to be done here by hand, because a
 * tool call passes through none of it:
 * <ul>
 *   <li>the caller comes from the transport context {@link McpServerConfig} filled on the request
 *       thread, not from the thread the tool happens to run on;</li>
 *   <li>its organization is entered as the tenant, and its authentication as the security context,
 *       for the duration of the call and no longer — audit and quota read the latter;</li>
 *   <li>a write is refused for a READ_ONLY key and for a suspended organization, which is what
 *       {@code ScopeEnforcementInterceptor} does for a handler carrying
 *       {@code @RequireScope(READ_WRITE)};</li>
 *   <li>the project is always the key's own. No tool takes a project id, so there is nothing for a
 *       caller to point at another project with.</li>
 * </ul>
 *
 * <p>Results are JSON written by the same Jackson 2 mapper the REST API writes with, so a tool
 * returns exactly the shapes {@code openapi.yaml} documents. Failures come back as tool errors
 * rather than protocol errors: the model is meant to read them and correct its call.
 */
@Slf4j
@Component
public class McpCaller {

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final SuspensionCheck suspensionCheck;

    public McpCaller(ObjectMapper objectMapper, Validator validator, SuspensionCheck suspensionCheck) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.suspensionCheck = suspensionCheck;
    }

    /** A tool that only reads. Any key of the project may call it. */
    public CallToolResult read(McpTransportContext context, Function<AuthContext, Object> body) {
        return run(context, null, body);
    }

    /** A tool that changes something. Refused for a READ_ONLY key and a suspended organization. */
    public CallToolResult write(McpTransportContext context, String tool, Function<AuthContext, Object> body) {
        return run(context, tool, body);
    }

    /** Bean-validates a request DTO the way {@code @Valid} would have on the REST route. */
    public <T> T valid(T request) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new McpToolException("Invalid arguments: " + violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; ")));
        }
        return request;
    }

    private CallToolResult run(McpTransportContext context, String writeTool, Function<AuthContext, Object> body) {
        Object caller = context == null ? null : context.get(McpServerConfig.CALLER);
        if (!(caller instanceof ApiKeyAuthenticationToken apiKey)) {
            return error("This MCP server needs a project API key ('Authorization: Bearer <key>' or "
                    + "'X-API-Key: <key>') or an OAuth access token from signing in to Railhook.");
        }

        AuthContext auth = new AuthContext(null, apiKey.getOrganizationId(), MembershipRole.API_KEY,
                apiKey.getProjectId(), apiKey.getScope());

        if (writeTool != null) {
            if (apiKey.getScope() != ApiKeyScope.READ_WRITE) {
                return error(apiKey instanceof McpOAuthAuthenticationToken
                        ? writeTool + " changes data, and this app was connected with read-only access. "
                                + "Reconnect it and choose Read & write to use it; the read tools work either way."
                        : writeTool + " changes data, and this API key is READ_ONLY. "
                                + "Use a READ_WRITE key for it; the read tools work with either.");
            }
            String suspended = TenantContext.callAs(apiKey.getOrganizationId(),
                    () -> suspensionCheck.suspensionReason(apiKey.getOrganizationId()).orElse(null));
            if (suspended != null) {
                return error("This organization is suspended and cannot make changes."
                        + (suspended.isBlank() ? "" : " Reason: " + suspended));
            }
        }

        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext scoped = SecurityContextHolder.createEmptyContext();
        scoped.setAuthentication(apiKey);
        SecurityContextHolder.setContext(scoped);
        try {
            Object result = TenantContext.callAs(apiKey.getOrganizationId(), () -> body.apply(auth));
            return CallToolResult.builder().addTextContent(json(result)).build();
        } catch (McpToolException | IllegalArgumentException | NotFoundException | ForbiddenException
                 | ConflictException | UnauthorizedException | QuotaExceededException
                 | UrlValidator.InvalidUrlException e) {
            return error(e.getMessage());
        } catch (DataIntegrityViolationException e) {
            return error("The request conflicts with the current state of the resource.");
        } catch (RuntimeException e) {
            log.error("MCP tool call failed for project {}: {}", apiKey.getProjectId(), e.getMessage(), e);
            return error("An unexpected error occurred. Nothing about it is specific to the arguments; "
                    + "retrying later may work.");
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the tool result", e);
        }
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().isError(true).addTextContent(message).build();
    }

    /** An argument the tool itself rejects; its message is shown to the model as-is. */
    public static class McpToolException extends RuntimeException {
        public McpToolException(String message) {
            super(message);
        }
    }
}
