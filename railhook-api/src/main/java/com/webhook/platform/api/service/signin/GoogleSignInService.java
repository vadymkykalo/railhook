package com.webhook.platform.api.service.signin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.service.ExternalSignInService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Authorization code with PKCE and a nonce; the browser ends up with a one-time code, never a token. */
@Service
@Slf4j
public class GoogleSignInService {

    public static final String CALLBACK_PATH = "/api/v1/auth/oauth/google/callback";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final String clientId;
    private final String clientSecret;
    private final String authorizationUri;
    private final String tokenUri;
    private final String jwksUri;
    private final String appBaseUrl;
    private final WebClient webClient;
    private final OAuthStateCodec stateCodec;
    private final GoogleIdTokenVerifier idTokenVerifier;
    private final ExternalSignInService externalSignInService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GoogleSignInService(
            @Value("${google.oauth.client-id:}") String clientId,
            @Value("${google.oauth.client-secret:}") String clientSecret,
            @Value("${google.oauth.authorization-uri:https://accounts.google.com/o/oauth2/v2/auth}") String authorizationUri,
            @Value("${google.oauth.token-uri:https://oauth2.googleapis.com/token}") String tokenUri,
            @Value("${google.oauth.jwks-uri:https://www.googleapis.com/oauth2/v3/certs}") String jwksUri,
            @Value("${app.base-url:http://localhost:5173}") String appBaseUrl,
            @Value("${jwt.secret}") String jwtSecret,
            WebClient.Builder webClientBuilder,
            ExternalSignInService externalSignInService) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.authorizationUri = authorizationUri;
        this.tokenUri = tokenUri;
        this.jwksUri = jwksUri;
        this.appBaseUrl = appBaseUrl.replaceAll("/+$", "");
        this.webClient = webClientBuilder.build();
        this.stateCodec = new OAuthStateCodec(jwtSecret, Clock.systemUTC());
        this.idTokenVerifier = new GoogleIdTokenVerifier(this::fetchKeySet, this.clientId, Clock.systemUTC());
        this.externalSignInService = externalSignInService;
    }

    /** Without both halves the button is hidden and the endpoints answer 404. */
    public boolean isEnabled() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    /** Must match the URI registered with Google character for character. */
    public String redirectUri() {
        return appBaseUrl + CALLBACK_PATH;
    }

    public record Start(String authorizationUrl, String stateCookie) {
    }

    public Start start(String intent, String returnTo) {
        OAuthState state = stateCodec.newState(returnTo, intent);
        String url = UriComponentsBuilder.fromUriString(authorizationUri)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state.state())
                .queryParam("nonce", state.nonce())
                .queryParam("code_challenge", state.codeChallenge())
                .queryParam("code_challenge_method", "S256")
                .queryParam("prompt", "select_account")
                .build()
                .encode()
                .toUriString();
        return new Start(url, stateCodec.encode(state));
    }

    /** {@code browserBinding} is what the browser must hold for the one-time code to work. */
    public record Completion(String location, String browserBinding) {
        static Completion refused(String location) {
            return new Completion(location, null);
        }
    }

    /** Always a path on this origin; a failure reveals only its error code. */
    public Completion complete(String code, String state, String error, String stateCookie) {
        Optional<OAuthState> saved = stateCodec.decode(stateCookie);
        String startPage = saved.map(OAuthState::intent).filter("register"::equals).map(i -> "/register").orElse("/login");
        try {
            OAuthState expected = saved.orElseThrow(() ->
                    new SignInRejectedException(SignInFailure.STATE, "no valid state cookie"));
            if (state == null || !MessageDigest.isEqual(
                    state.getBytes(StandardCharsets.UTF_8), expected.state().getBytes(StandardCharsets.UTF_8))) {
                throw new SignInRejectedException(SignInFailure.STATE, "state does not match the cookie");
            }
            if (error != null && !error.isBlank()) {
                throw new SignInRejectedException(SignInFailure.DENIED, "Google answered " + error);
            }
            if (code == null || code.isBlank()) {
                throw new SignInRejectedException(SignInFailure.DENIED, "no authorization code");
            }

            VerifiedIdentity identity = idTokenVerifier.verify(exchangeCode(code, expected.codeVerifier()), expected.nonce());

            Optional<UUID> existing = externalSignInService.findOrLinkVerifiedIdentity(identity);
            UUID userId = existing.orElseGet(() -> externalSignInService.registerWithVerifiedIdentity(
                    identity, NewAccountOrganizationName.of(identity)));
            boolean created = existing.isEmpty();
            String handoff = externalSignInService.issueSignInHandoff(userId, created);

            UriComponentsBuilder landing = UriComponentsBuilder.fromPath("/auth/callback")
                    .queryParam("code", handoff)
                    .queryParam("returnTo", expected.returnTo());
            if (created) {
                landing.queryParam("new", "1");
            }
            return new Completion(landing.build().encode().toUriString(),
                    ExternalSignInService.browserBindingFor(handoff));
        } catch (SignInRejectedException e) {
            log.warn("Google sign-in refused: {} ({})", e.failure(), e.getMessage());
            return Completion.refused(startPage + "?error=" + e.failure().code());
        } catch (RuntimeException e) {
            log.error("Google sign-in failed", e);
            return Completion.refused(startPage + "?error=" + SignInFailure.UNAVAILABLE.code());
        }
    }

    private String exchangeCode(String code, String codeVerifier) {
        try {
            String body = webClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("grant_type", "authorization_code")
                            .with("code", code)
                            .with("client_id", clientId)
                            .with("client_secret", clientSecret)
                            .with("redirect_uri", redirectUri())
                            .with("code_verifier", codeVerifier))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(HTTP_TIMEOUT);
            String idToken = body == null ? null : objectMapper.readTree(body).path("id_token").asText(null);
            if (idToken == null || idToken.isBlank()) {
                throw new SignInRejectedException(SignInFailure.UNAVAILABLE, "token response carried no id_token");
            }
            return idToken;
        } catch (SignInRejectedException e) {
            throw e;
        } catch (Exception e) {
            throw new SignInRejectedException(SignInFailure.UNAVAILABLE, "code exchange failed: " + e.getMessage());
        }
    }

    private String fetchKeySet() {
        return webClient.get().uri(jwksUri).retrieve().bodyToMono(String.class).block(HTTP_TIMEOUT);
    }
}
