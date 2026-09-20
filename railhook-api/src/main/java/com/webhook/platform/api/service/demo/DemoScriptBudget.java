package com.webhook.platform.api.service.demo;

import com.webhook.platform.api.security.DemoSessions;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.AuthRateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * How much JavaScript a stranger may run on our servers.
 *
 * <p>The public demo can reach exactly two handlers that execute code — the Transform Studio's
 * preview and the delivery dry-run — and both run the script on the request thread that called
 * them. The sandbox bounds one run (wall clock and allocation, see {@code ScriptLimits}); it
 * says nothing about how many runs a visitor may start, and a loop of scripts that each burn the
 * whole time budget is a way to hold Tomcat's request threads rather than merely to spend CPU.
 *
 * <p>So the count is bounded too, per address and per session, in Redis like every other
 * anonymous budget on this API — see {@link AuthRateLimiterService#allowDemoScriptRun} for the
 * two numbers and the arithmetic behind them. Nothing changes for a signed-in caller: their runs
 * are already attributable to an account, and the organization limiter bounds them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoScriptBudget {

    private static final String BEARER = "Bearer ";

    private final AuthRateLimiterService authRateLimiterService;
    private final TrustedProxyResolver trustedProxyResolver;

    /**
     * Spends one script run for a demo caller, and does nothing for anybody else.
     *
     * @return whether the caller is the demo, which is also what decides whether the dry-run's
     *         signature is masked — asked once here so a handler does not answer it twice
     * @throws ResponseStatusException 429, when the address or the session is out of runs
     */
    public boolean spendIfDemo(HttpServletRequest request) {
        if (!DemoSessions.isCurrent()) {
            return false;
        }
        String ip = trustedProxyResolver.resolve(request);
        if (!authRateLimiterService.allowDemoScriptRun(ip, bearerToken(request))) {
            log.debug("Demo script run refused: {} is out of runs", ip);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many transformation runs in this demo session. Try again in a minute.");
        }
        return true;
    }

    /**
     * The demo session's own identity. A demo token carries a {@code jti} of its own, so the
     * bearer string distinguishes one session from another where the user and organization ids
     * cannot — every demo session is the same person in the same organization.
     */
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        return header.substring(BEARER.length()).trim();
    }
}
