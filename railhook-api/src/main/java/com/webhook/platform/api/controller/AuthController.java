package com.webhook.platform.api.controller;

import com.webhook.platform.api.security.DemoSessions;
import com.webhook.platform.api.security.AllowedInDemo;
import com.webhook.platform.api.domain.enums.SessionClient;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.ChangePasswordRequest;
import com.webhook.platform.api.dto.CurrentUserResponse;
import com.webhook.platform.api.dto.ExchangeSignInCodeRequest;
import com.webhook.platform.api.dto.ForgotPasswordRequest;
import com.webhook.platform.api.dto.LoginRequest;
import com.webhook.platform.api.dto.LogoutRequest;
import com.webhook.platform.api.dto.RefreshTokenRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.api.dto.ResetPasswordRequest;
import com.webhook.platform.api.dto.SessionResponse;
import com.webhook.platform.api.dto.SwitchOrganizationRequest;
import com.webhook.platform.api.dto.UpdateProfileRequest;
import com.webhook.platform.api.dto.UserResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.AuthCookies;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.AuthRateLimiterService;
import com.webhook.platform.api.service.captcha.CaptchaVerifier;
import com.webhook.platform.api.service.AuthService;
import com.webhook.platform.api.service.SessionOrigin;
import com.webhook.platform.api.service.AccountErasureService;
import com.webhook.platform.api.service.ExternalSignInService;
import com.webhook.platform.api.service.PlatformAdminAccessService;
import com.webhook.platform.api.service.UserSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "User registration and login")
public class AuthController {

    private final AuthService authService;
    private final UserSessionService userSessionService;
    private final AccountErasureService accountErasureService;
    private final AuthRateLimiterService authRateLimiterService;
    private final TrustedProxyResolver trustedProxyResolver;
    private final CaptchaVerifier captchaVerifier;
    private final ExternalSignInService externalSignInService;
    private final PlatformAdminAccessService platformAdminAccessService;
    private final AuthCookies authCookies;

    public AuthController(
            AuthService authService,
            UserSessionService userSessionService,
            AccountErasureService accountErasureService,
            AuthRateLimiterService authRateLimiterService,
            TrustedProxyResolver trustedProxyResolver,
            CaptchaVerifier captchaVerifier,
            ExternalSignInService externalSignInService,
            PlatformAdminAccessService platformAdminAccessService,
            AuthCookies authCookies) {
        this.authService = authService;
        this.userSessionService = userSessionService;
        this.accountErasureService = accountErasureService;
        this.authRateLimiterService = authRateLimiterService;
        this.trustedProxyResolver = trustedProxyResolver;
        this.captchaVerifier = captchaVerifier;
        this.externalSignInService = externalSignInService;
        this.platformAdminAccessService = platformAdminAccessService;
        this.authCookies = authCookies;
    }

    @Operation(summary = "Register new user", description = "Creates a new user account and organization")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User registered successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request or email already exists")
    })
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String clientIp = getClientIp(httpRequest);
        if (!authRateLimiterService.allowRegister(clientIp)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many registration attempts. Try again later.");
        }
        // Unconfigured, the verifier accepts everything, so self-hosting is unaffected.
        if (!captchaVerifier.verify(request.getCaptchaToken(), clientIp)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CAPTCHA verification failed. Please try again.");
        }
        try {
            AuthResponse response = authService.register(request, originOf(httpRequest));
            setRefreshTokenCookie(httpResponse, response.getRefreshToken());
            response.setRefreshToken(null);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            log.error("Registration failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Operation(summary = "Login", description = "Authenticates user and returns JWT token")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (!authRateLimiterService.allowLogin(getClientIp(httpRequest), request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many login attempts. Try again later.");
        }
        try {
            AuthResponse response = authService.login(request, originOf(httpRequest));
            setRefreshTokenCookie(httpResponse, response.getRefreshToken());
            response.setRefreshToken(null);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Login failed: {}", e.getMessage());
            throw e;
        }
    }

    @Operation(summary = "Exchange a sign-in code",
            description = "Trades the one-time code the Google sign-in callback put in the dashboard's URL for the "
                    + "same session a password sign-in returns: the access token in the body, the refresh token in "
                    + "its cookie. A code works once, within 60 seconds of the callback, and only in the browser the "
                    + "callback redirected: it must carry the `railhook_signin_handoff` cookie the callback set.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in"),
            @ApiResponse(responseCode = "401", description = "The code has been used, has expired, or was opened in another browser"),
            @ApiResponse(responseCode = "429", description = "Too many requests")
    })
    @PostMapping("/oauth/exchange")
    public ResponseEntity<AuthResponse> exchangeSignInCode(@Valid @RequestBody ExchangeSignInCodeRequest request,
            @CookieValue(value = AuthCookies.SIGN_IN_HANDOFF, required = false) String browserBinding,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (!authRateLimiterService.allowTokenAction(getClientIp(httpRequest), request.getCode())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
        }
        // Good for one attempt, whatever its outcome.
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, authCookies.clearedSignInHandoff().toString());
        AuthResponse response = externalSignInService.exchangeSignInHandoff(
                request.getCode(), browserBinding, originOf(httpRequest));
        setRefreshTokenCookie(httpResponse, response.getRefreshToken());
        response.setRefreshToken(null);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Refresh token", description = "Exchanges a valid refresh token for new access and refresh tokens")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tokens refreshed successfully"),
            @ApiResponse(responseCode = "401", description = "Invalid or expired refresh token")
    })
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(@CookieValue(value = "refresh_token", required = false) String cookieRefreshToken,
            @RequestBody(required = false) RefreshTokenRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String refreshToken = cookieRefreshToken != null ? cookieRefreshToken :
                (request != null ? request.getRefreshToken() : null);
        // Its own bucket: refresh runs on every page load.
        if (!authRateLimiterService.allowRefresh(getClientIp(httpRequest), refreshToken)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
        }
        try {
            if (refreshToken == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token missing");
            }
            AuthResponse response = authService.refreshToken(refreshToken, originOf(httpRequest));
            setRefreshTokenCookie(httpResponse, response.getRefreshToken());
            // The CLI has no cookie jar; without the rotated token in the body it would replay the
            // old one and trip reuse detection.
            if (cookieRefreshToken != null) {
                response.setRefreshToken(null);
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Token refresh failed: {}", e.getMessage());
            throw e;
        }
    }

    @Operation(summary = "Logout", description = "Revokes access and refresh tokens")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Logged out successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @AllowedInDemo(reason = "ends the caller's own demo session; the demo branch below touches nothing else")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(value = "refresh_token", required = false) String cookieRefreshToken,
            @RequestBody(required = false) LogoutRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String authHeader = httpRequest.getHeader("Authorization");
        String accessToken = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7)
                : null;
        if (DemoSessions.isCurrent()) {
            // Leave the refresh cookie alone: it belongs to the browser's real account.
            authService.logout(accessToken, null);
            return ResponseEntity.noContent().build();
        }
        String refreshToken = cookieRefreshToken != null ? cookieRefreshToken
                : (request != null ? request.getRefreshToken() : null);

        authService.logout(accessToken, refreshToken);
        clearRefreshTokenCookie(httpResponse);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Verify email", description = "Verifies user email with the token sent to their email")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Email verified successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid or expired token")
    })
    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@RequestParam("token") String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Resend verification email", description = "Sends a new verification email to the user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verification email sent"),
            @ApiResponse(responseCode = "400", description = "Email already verified"),
            @ApiResponse(responseCode = "404", description = "User not found"),
            @ApiResponse(responseCode = "429", description = "Too many requests")
    })
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@RequestParam("email") String email,
            HttpServletRequest httpRequest) {
        if (!authRateLimiterService.allowLogin(getClientIp(httpRequest), email)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
        }
        authService.resendVerification(email);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Change password", description = "Changes the authenticated user's password")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password changed successfully"),
            @ApiResponse(responseCode = "400", description = "Current password incorrect or validation failed"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            AuthContext auth) {
        authService.changePassword(auth.requireUserId(), request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Update profile", description = "Updates the authenticated user's profile information")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile updated successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PutMapping("/profile")
    public ResponseEntity<UserResponse> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            AuthContext auth) {
        UserResponse response = authService.updateProfile(auth.requireUserId(), request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get current user", description = "Returns information about the authenticated user")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User info returned"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> getCurrentUser(AuthContext auth) {
        CurrentUserResponse response = authService.getCurrentUser(
                auth.requireUserId(),
                auth.role());
        response.setPlatformAdmin(platformAdminAccessService.offersPanelTo(auth.requireUserId()));
        response.setDemo(DemoSessions.isCurrent());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "List active sessions",
            description = "Returns every live sign-in for the authenticated user — browser sessions and "
                    + "CLI device-code grants alike — with the session making the request flagged as current. "
                    + "No token material is returned.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sessions returned"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/sessions")
    public ResponseEntity<List<SessionResponse>> listSessions(
            @CookieValue(value = "refresh_token", required = false) String cookieRefreshToken,
            AuthContext auth) {
        return ResponseEntity.ok(authService.listSessions(auth.requireUserId(), cookieRefreshToken));
    }

    @Operation(summary = "Revoke a session",
            description = "Signs one device out. The session's refresh token stops working and so does the "
                    + "access token it already issued, rather than surviving until its own expiry.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Session revoked"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "No such session for this user")
    })
    // READ: a Viewer must be able to sign out their own devices.
    @RequireAccess(AccessLevel.READ)
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(@PathVariable("sessionId") UUID sessionId, AuthContext auth) {
        auth.requireJwt();
        userSessionService.revokeSession(auth.requireUserId(), sessionId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Sign out everywhere",
            description = "Ends every session for the authenticated user, including the one making the call.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "All sessions revoked"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @RequireAccess(AccessLevel.READ)
    @PostMapping("/sessions/revoke-all")
    public ResponseEntity<Void> revokeAllSessions(AuthContext auth, HttpServletResponse httpResponse) {
        auth.requireJwt();
        userSessionService.revokeAllSessions(auth.requireUserId());
        clearRefreshTokenCookie(httpResponse);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "eraseOwnAccount", summary = "Erase your account",
            description = "GDPR Article 17. Permanently removes the personal data on the account and "
                    + "makes it unusable: the address is replaced with an unroutable one, the name is "
                    + "dropped, every session is closed and every membership is removed. Any "
                    + "organization you were the only member of is deleted with it, including every "
                    + "project, endpoint, event and delivery under it. This cannot be undone.\n\n"
                    + "Refused with 409 if you are the last owner of an organization that still has "
                    + "other members: hand it over first, or the people left behind can neither "
                    + "administer nor delete it.\n\n"
                    + "The audit log keeps a record that this happened. It names no contact details "
                    + "and is what lets the erasure be shown to have been carried out.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Account erased"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "409", description = "Last owner of an organization that has other members")
    })
    @RequireAccess(AccessLevel.READ)
    @DeleteMapping("/me")
    public ResponseEntity<Void> eraseOwnAccount(AuthContext auth, HttpServletResponse httpResponse) {
        // An API key must not be able to erase the person who created it.
        auth.requireJwt();
        accountErasureService.eraseAccount(auth.requireUserId());
        clearRefreshTokenCookie(httpResponse);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Switch organization",
            description = "Re-issues an access token scoped to another organization the caller belongs to. "
                    + "The refresh token is untouched and no other session is affected; the new token's role "
                    + "comes from the membership in the target organization, not from the token being replaced.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Access token re-issued for the target organization"),
            @ApiResponse(responseCode = "401", description = "Not authenticated, or no live session"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of that organization")
    })
    @RequireAccess(AccessLevel.READ)
    @PostMapping("/switch-organization")
    public ResponseEntity<AuthResponse> switchOrganization(
            @Valid @RequestBody SwitchOrganizationRequest request,
            @CookieValue(value = "refresh_token", required = false) String cookieRefreshToken,
            AuthContext auth) {
        auth.requireJwt();
        AuthResponse response = authService.switchOrganization(
                auth.requireUserId(), request, cookieRefreshToken);
        // No new refresh cookie: the organization lives on the session row, and rotating here
        // would make a double click look like token reuse.
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Forgot password", description = "Sends a password reset email to the user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "If the email exists, a reset link has been sent"),
            @ApiResponse(responseCode = "429", description = "Too many requests")
    })
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        if (!authRateLimiterService.allowLogin(getClientIp(httpRequest), request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests. Try again later.");
        }
        authService.forgotPassword(request.getEmail());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Reset password", description = "Resets user password using the token from the reset email")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password reset successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid or expired token")
    })
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest) {
        if (!authRateLimiterService.allowTokenAction(getClientIp(httpRequest), request.getToken())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests. Try again later.");
        }
        authService.resetPassword(request.getToken(), request.getNewPassword());
        return ResponseEntity.ok().build();
    }

    private String getClientIp(HttpServletRequest request) {
        return trustedProxyResolver.resolve(request);
    }

    /** Display only: neither the User-Agent nor the IP is ever an authorization input. */
    private SessionOrigin originOf(HttpServletRequest request) {
        return SessionOrigin.of(SessionClient.WEB, request.getHeader("User-Agent"), getClientIp(request));
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, authCookies.refreshToken(refreshToken).toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, authCookies.clearedRefreshToken().toString());
    }
}
