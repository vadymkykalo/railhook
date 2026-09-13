package com.webhook.platform.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.AuditLogAspect;
import com.webhook.platform.api.service.AuthRateLimiterService;
import com.webhook.platform.api.service.PlatformAdminAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything that happens to a request on {@code /api/v1/admin/**} between "who is this" and
 * "may they": turning a listed person's sign-in into the platform-admin authority, slowing them
 * down, and writing down what they looked at.
 *
 * <p><b>Granting.</b> A request carrying a JWT gets {@link PlatformAdminUserAuthenticationToken}
 * only when {@link PlatformAdminAccessService#evaluate} says so, and only on these paths — the
 * same token on a tenant path stays an ordinary member's. When the only thing wrong is an old
 * sign-in the answer is a distinct {@code reauthentication_required} 403, so the panel can say
 * "sign in again" rather than "access denied" to the one person it is for. Anything else is left
 * alone and {@code SecurityConfig} refuses it like any other caller without the authority.
 *
 * <p><b>Rate limit.</b> Per admin user, or per address for the operator token. The panel is a
 * handful of requests per screen; a script walking every organization is not what it is for.
 *
 * <p><b>Audit.</b> Every request made with the authority — reads included, since reading another
 * organization's members is the sensitive act here — becomes a {@code PLATFORM_ADMIN_ACCESS} row:
 * who, from where, which organization or user, and what came back. Written under the system
 * tenant, so it does not appear in either the admin's or the subject's own audit log; writes that
 * change a tenant are additionally recorded against that tenant by {@code @Auditable}.
 *
 * <p>Not a {@code @Component}, for the reason given on {@code TenantContextFilter}.
 */
@Slf4j
public class PlatformAdminAccessFilter extends OncePerRequestFilter {

    static final String ADMIN_PREFIX = "/api/v1/admin/";

    private static final Pattern SUBJECT =
            Pattern.compile("^/api/v1/admin/(?:organizations|users)/([0-9a-fA-F-]{36})(?:/|$)");
    private static final String BEARER_PREFIX = "Bearer ";

    private final PlatformAdminAccessService access;
    private final JwtUtil jwtUtil;
    private final AuthRateLimiterService rateLimiter;
    private final AuditLogAspect audit;
    private final TrustedProxyResolver proxyResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlatformAdminAccessFilter(PlatformAdminAccessService access, JwtUtil jwtUtil,
            AuthRateLimiterService rateLimiter, AuditLogAspect audit, TrustedProxyResolver proxyResolver) {
        this.access = access;
        this.jwtUtil = jwtUtil;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.proxyResolver = proxyResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !pathOf(request).startsWith(ADMIN_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken jwt
                && !(authentication instanceof PlatformAdminUserAuthenticationToken)
                && access.isConfigured()) {
            PlatformAdminAccessService.Decision decision = access.evaluate(jwt.getUserId(), sessionIdOf(request));
            switch (decision.outcome()) {
                case GRANTED -> SecurityContextHolder.getContext()
                        .setAuthentication(new PlatformAdminUserAuthenticationToken(jwt, decision.email()));
                case REAUTHENTICATE -> {
                    writeError(response, HttpServletResponse.SC_FORBIDDEN, "reauthentication_required",
                            "Sign in again to use the platform admin panel: the last sign-in was more than "
                                    + PlatformAdminAccessService.MAX_SIGN_IN_AGE.toHours() + " hours ago.");
                    return;
                }
                case DENIED -> {
                    // Left as it is: SecurityConfig refuses a caller without the authority.
                }
            }
        }

        authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!holdsPlatformAdmin(authentication)) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = proxyResolver.resolve(request);
        String caller = authentication instanceof JwtAuthenticationToken jwt
                ? "user:" + jwt.getUserId()
                : "token:" + clientIp;
        if (!rateLimiter.allowPlatformAdmin(caller)) {
            log.warn("Platform admin rate limit exceeded for {}", caller);
            writeError(response, 429, "rate_limited", "Too many platform admin requests. Try again in a minute.");
            return;
        }

        long started = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            record(request, response, authentication, clientIp, (int) (System.currentTimeMillis() - started));
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, Authentication authentication,
            String clientIp, int durationMs) {
        try {
            String path = pathOf(request);
            UUID subject = null;
            Matcher matcher = SUBJECT.matcher(path);
            if (matcher.find()) {
                subject = UUID.fromString(matcher.group(1));
            }
            int status = response.getStatus();

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("method", request.getMethod());
            details.put("path", path);
            if (request.getQueryString() != null) {
                String query = request.getQueryString();
                details.put("query", query.length() > 500 ? query.substring(0, 500) : query);
            }
            details.put("status", status);
            UUID userId = null;
            if (authentication instanceof PlatformAdminUserAuthenticationToken admin) {
                userId = admin.getUserId();
                details.put("credential", "session");
                details.put("email", admin.getEmail());
            } else {
                details.put("credential", "operator-token");
            }

            audit.recordAsync(AuditAction.PLATFORM_ADMIN_ACCESS.name(), "PlatformAdmin", subject, userId, null,
                    status < 400 ? "SUCCESS" : "FAILURE", null, durationMs, clientIp,
                    objectMapper.writeValueAsString(details));
        } catch (Exception e) {
            log.warn("Failed to record platform admin access to {}", request.getRequestURI(), e);
        }
    }

    private UUID sessionIdOf(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        try {
            String raw = jwtUtil.parseToken(header.substring(BEARER_PREFIX.length()))
                    .get(JwtUtil.CLAIM_SESSION_ID, String.class);
            return raw == null ? null : UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean holdsPlatformAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> PlatformAdminAuthenticationToken.AUTHORITY.equals(a.getAuthority()));
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    private static void writeError(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\",\"status\":"
                + status + "}");
    }
}
