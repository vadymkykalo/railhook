package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.SignInProvidersResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.security.AuthCookies;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.AuthRateLimiterService;
import com.webhook.platform.api.service.signin.GoogleSignInService;
import com.webhook.platform.api.service.signin.OAuthStateCodec;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The browser-facing half of "Continue with Google". The dashboard's half — trading the one-time
 * code for a session — is {@code POST /api/v1/auth/oauth/exchange} on {@link AuthController},
 * beside the other endpoints that set the refresh cookie.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "User registration and login")
public class GoogleSignInController {

    private final GoogleSignInService googleSignInService;
    private final AuthRateLimiterService authRateLimiterService;
    private final TrustedProxyResolver trustedProxyResolver;
    private final AuthCookies authCookies;

    public GoogleSignInController(GoogleSignInService googleSignInService,
                                  AuthRateLimiterService authRateLimiterService,
                                  TrustedProxyResolver trustedProxyResolver,
                                  AuthCookies authCookies) {
        this.googleSignInService = googleSignInService;
        this.authRateLimiterService = authRateLimiterService;
        this.trustedProxyResolver = trustedProxyResolver;
        this.authCookies = authCookies;
    }

    @Operation(summary = "List sign-in providers",
            description = "Which identity providers the sign-in and registration pages offer on this deployment. "
                    + "Public, because the pages ask before anyone has signed in.")
    @GetMapping("/providers")
    public ResponseEntity<SignInProvidersResponse> providers() {
        return ResponseEntity.ok(new SignInProvidersResponse(googleSignInService.isEnabled()));
    }

    @Operation(summary = "Start signing in with Google",
            description = "Redirects the browser to Google. Signs in the account the Google identity belongs to, "
                    + "and creates one — with its own organization — when there is none. `returnTo` is a path on "
                    + "this site to land on afterwards; anything else is replaced with the dashboard. Answers 404 "
                    + "when Google sign-in is not configured.")
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "Redirect to Google"),
            @ApiResponse(responseCode = "404", description = "Google sign-in is not configured on this deployment"),
            @ApiResponse(responseCode = "429", description = "Too many requests")
    })
    @GetMapping("/oauth/google/start")
    public ResponseEntity<Void> startGoogleSignIn(
            @RequestParam(value = "intent", required = false) String intent,
            @RequestParam(value = "returnTo", required = false) String returnTo,
            HttpServletRequest request,
            HttpServletResponse response) {
        requireEnabled();
        if (!authRateLimiterService.allowTokenAction(trustedProxyResolver.resolve(request), "google-start")) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
        }
        GoogleSignInService.Start start = googleSignInService.start(intent, returnTo);
        response.addHeader(HttpHeaders.SET_COOKIE,
                authCookies.signInState(start.stateCookie(), OAuthStateCodec.LIFETIME).toString());
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, start.authorizationUrl()).build();
    }

    @Operation(summary = "Google sign-in callback",
            description = "Where Google returns the browser. Redirects to the dashboard's `/auth/callback` with a "
                    + "one-time code valid for 60 seconds, or back to the sign-in page with `error=google_…`. "
                    + "Tokens are never put in a URL.")
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "Redirect to the dashboard or back to the sign-in page"),
            @ApiResponse(responseCode = "404", description = "Google sign-in is not configured on this deployment"),
            @ApiResponse(responseCode = "429", description = "Too many requests")
    })
    @GetMapping("/oauth/google/callback")
    public ResponseEntity<Void> googleSignInCallback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @CookieValue(value = AuthCookies.SIGN_IN_STATE, required = false) String stateCookie,
            HttpServletRequest request,
            HttpServletResponse response) {
        requireEnabled();
        if (!authRateLimiterService.allowTokenAction(trustedProxyResolver.resolve(request),
                state == null ? "google-callback" : state)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
        }
        String location = googleSignInService.complete(code, state, error, stateCookie);
        // Spent either way: a state is good for one callback.
        response.addHeader(HttpHeaders.SET_COOKIE, authCookies.clearedSignInState().toString());
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    private void requireEnabled() {
        if (!googleSignInService.isEnabled()) {
            throw new NotFoundException("Google sign-in is not configured on this deployment");
        }
    }
}
