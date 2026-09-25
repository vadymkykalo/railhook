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
 * A tool call passes through none of the web layer, so this does its work by hand: the caller
 * comes from the transport context (not the thread the tool runs on), the tenant and security
 * context are entered for the call only, and writes are refused for READ_ONLY keys and suspended
 * organizations. No tool takes a project id, so a call cannot reach another project.
 *
 * <p>Failures are tool errors rather than protocol errors so the model can read them and retry.
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

    public CallToolResult read(McpTransportContext context, Function<AuthContext, Object> body) {
        return run(context, null, body);
    }

    public CallToolResult write(McpTransportContext context, String tool, Function<AuthContext, Object> body) {
        return run(context, tool, body);
    }

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

    /** Its message is shown to the model as-is. */
    public static class McpToolException extends RuntimeException {
        public McpToolException(String message) {
            super(message);
        }
    }
}
