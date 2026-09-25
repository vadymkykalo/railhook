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

/** The sandbox bounds one run, not how many; a loop of demo scripts would hold Tomcat's threads. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoScriptBudget {

    private static final String BEARER = "Bearer ";

    private final AuthRateLimiterService authRateLimiterService;
    private final TrustedProxyResolver trustedProxyResolver;

    /** @return whether the caller is the demo, which also decides whether the dry-run signature is masked */
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

    // Every demo session is the same user and organization, so the token itself tells them apart.
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        return header.substring(BEARER.length()).trim();
    }
}
