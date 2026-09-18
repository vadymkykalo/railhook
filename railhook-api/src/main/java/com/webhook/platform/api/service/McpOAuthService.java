package com.webhook.platform.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.AuditLogAspect;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.OAuthAuthorizationRequest;
import com.webhook.platform.api.domain.entity.OAuthClient;
import com.webhook.platform.api.domain.entity.OAuthGrant;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OAuthAuthorizationRequestRepository;
import com.webhook.platform.api.domain.repository.OAuthClientRepository;
import com.webhook.platform.api.domain.repository.OAuthGrantRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.McpConsentApproveRequest;
import com.webhook.platform.api.dto.McpConsentDecisionResponse;
import com.webhook.platform.api.dto.McpConsentRequestResponse;
import com.webhook.platform.api.dto.McpGrantResponse;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.mcp.oauth.McpOAuthAuthenticationToken;
import com.webhook.platform.api.mcp.oauth.McpOAuthSettings;
import com.webhook.platform.api.mcp.oauth.OAuthProtocolException;
import com.webhook.platform.api.mcp.oauth.OAuthSecrets;
import com.webhook.platform.api.mcp.oauth.RedirectUris;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The OAuth 2.1 authorization server behind {@code /mcp}: what lets claude.ai, Claude Desktop's
 * connector screen and ChatGPT connect with a browser sign-in instead of a pasted API key.
 *
 * <p><b>Why this is hand-rolled rather than Spring Authorization Server.</b> SAS (now
 * {@code spring-security-oauth2-authorization-server} 7.x) builds against Boot 4.1 and was the
 * first choice; it fits the parts of this flow that are easy and fights the parts that are not:
 * <ul>
 *   <li>Its authorization endpoint expects the person to be a Spring Security principal on the
 *       browser request itself, i.e. an HTTP session. The dashboard has none: it signs in with a
 *       JWT held in memory and a refresh cookie scoped to {@code /api/v1/auth}. Bridging that would
 *       mean adding session login to the API for this one screen.</li>
 *   <li>Its client registration endpoint requires an initial access token; open registration, which
 *       MCP clients need, is a custom authentication provider on top.</li>
 *   <li>Its authorization store is one untyped, untenanted row per authorization. Here a grant is a
 *       tenant-scoped row the dashboard lists and revokes per project, exactly like an API key.</li>
 * </ul>
 * With those replaced, what SAS would still contribute is PKCE, code exchange and refresh rotation
 * — a few hundred lines, all of them pinned by {@code McpOAuthIntegrationTest}. So this is only
 * the subset MCP clients use: authorization code with PKCE S256, refresh tokens with rotation,
 * open registration of public or secret-holding clients, and revocation. No implicit flow, no
 * client credentials, no OpenID Connect.
 *
 * <p><b>Tokens</b> are opaque random strings stored as SHA-256 hashes, like API keys — not JWTs.
 * A token is only ever checked by this service, so there is nothing a signature would save, and
 * an opaque token is revoked the moment its row says so.
 *
 * <p><b>A grant is an API key with a person behind it.</b> It names one project and one
 * {@link ApiKeyScope}, which {@code McpCaller} enforces as it does for a key. Unlike a key it is
 * re-checked against its approver on every use: it stops working when they leave the organization
 * or are suspended, and a READ_WRITE grant stops when they lose the role that may create a key.
 */
@Slf4j
@Service
public class McpOAuthService {

    private static final String RESOURCE_TYPE = "McpGrant";
    private static final Set<String> AUTH_METHODS = Set.of("none", "client_secret_post", "client_secret_basic");
    private static final Set<String> GRANT_TYPES = Set.of("authorization_code", "refresh_token");
    private static final Set<MembershipRole> WRITER_ROLES = Set.of(MembershipRole.OWNER, MembershipRole.DEVELOPER);
    private static final int MAX_REDIRECT_URIS = 10;
    /** last_used_at is for a person reading a list, not an access log: once a minute is plenty. */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final McpOAuthSettings settings;
    private final OAuthClientRepository clientRepository;
    private final OAuthAuthorizationRequestRepository requestRepository;
    private final OAuthGrantRepository grantRepository;
    private final ProjectRepository projectRepository;
    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final AuditLogAspect auditLog;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public McpOAuthService(McpOAuthSettings settings,
                           OAuthClientRepository clientRepository,
                           OAuthAuthorizationRequestRepository requestRepository,
                           OAuthGrantRepository grantRepository,
                           ProjectRepository projectRepository,
                           MembershipRepository membershipRepository,
                           UserRepository userRepository,
                           AuditLogAspect auditLog,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.settings = settings;
        this.clientRepository = clientRepository;
        this.requestRepository = requestRepository;
        this.grantRepository = grantRepository;
        this.projectRepository = projectRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ── dynamic client registration (RFC 7591) ───────────────────────────

    /**
     * Registers an app. Unknown metadata is ignored, as RFC 7591 §2 asks; grant and response types
     * are narrowed to the ones served here rather than refused, and the response says what was
     * actually registered.
     */
    @SystemTenant("an app registers before any person or organization is involved -- oauth_clients is not tenant-scoped")
    @Transactional
    public Map<String, Object> registerClient(Map<String, Object> metadata) {
        List<String> redirectUris = stringList(metadata.get("redirect_uris"));
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new OAuthProtocolException("invalid_redirect_uri", "redirect_uris is required");
        }
        if (redirectUris.size() > MAX_REDIRECT_URIS) {
            throw new OAuthProtocolException("invalid_redirect_uri", "At most " + MAX_REDIRECT_URIS + " redirect_uris");
        }
        for (String uri : redirectUris) {
            String problem = RedirectUris.problemWith(uri);
            if (problem != null) {
                throw new OAuthProtocolException("invalid_redirect_uri", problem);
            }
        }

        String authMethod = metadata.get("token_endpoint_auth_method") instanceof String s ? s : "client_secret_basic";
        if (!AUTH_METHODS.contains(authMethod)) {
            throw new OAuthProtocolException("invalid_client_metadata",
                    "token_endpoint_auth_method must be one of " + String.join(", ", AUTH_METHODS));
        }

        List<String> requestedGrants = stringList(metadata.get("grant_types"));
        List<String> grantTypes = requestedGrants == null
                ? List.of("authorization_code", "refresh_token")
                : requestedGrants.stream().filter(GRANT_TYPES::contains).distinct().toList();
        if (!grantTypes.contains("authorization_code")) {
            throw new OAuthProtocolException("invalid_client_metadata",
                    "Only the authorization_code grant (with refresh_token) is supported");
        }

        String clientName = clean(metadata.get("client_name") instanceof String s ? s : null, 200);
        String clientUri = metadata.get("client_uri") instanceof String s && isHttpsUrl(s) && s.length() <= 2000 ? s : null;

        String clientId = OAuthSecrets.mint(OAuthSecrets.CLIENT_ID_PREFIX, 18);
        String clientSecret = authMethod.equals("none") ? null : OAuthSecrets.mint(OAuthSecrets.CLIENT_SECRET_PREFIX);

        OAuthClient client = clientRepository.save(OAuthClient.builder()
                .clientId(clientId)
                .clientSecretHash(clientSecret == null ? null : OAuthSecrets.hash(clientSecret))
                .tokenEndpointAuthMethod(authMethod)
                .clientName(clientName == null || clientName.isBlank() ? "MCP app" : clientName)
                .clientUri(clientUri)
                .redirectUris(String.join("\n", redirectUris))
                .build());
        log.info("Registered OAuth client {} ({}) redirecting to {}", client.getClientId(), client.getClientName(),
                redirectUris.stream().map(RedirectUris::hostOf).distinct().collect(Collectors.joining(", ")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client_id", clientId);
        response.put("client_id_issued_at", now().getEpochSecond());
        if (clientSecret != null) {
            response.put("client_secret", clientSecret);
            response.put("client_secret_expires_at", 0);
        }
        response.put("client_name", client.getClientName());
        if (clientUri != null) {
            response.put("client_uri", clientUri);
        }
        response.put("redirect_uris", redirectUris);
        response.put("grant_types", grantTypes);
        response.put("response_types", List.of("code"));
        response.put("token_endpoint_auth_method", authMethod);
        return response;
    }

    // ── authorization endpoint ───────────────────────────────────────────

    /**
     * Checks an authorization request and parks it for the consent screen. Returns where to send
     * the browser: the consent screen, or — once the redirect URI is known to be the app's own —
     * straight back to the app with an error.
     *
     * <p>Until the client and its redirect URI are verified, an error is never sent to the
     * redirect URI: that would make this endpoint an open redirector (RFC 6749 §4.1.2.1). The
     * consent screen shows it instead.
     */
    @SystemTenant("the browser arrives from the app before anyone has signed in -- the request row is not tenant-scoped")
    @Transactional
    public String authorize(Map<String, String> params) {
        Instant now = now();
        // Housekeeping rides along with the traffic that creates the rows, so abandoned consent
        // screens and never-exchanged codes do not pile up without a scheduler of their own.
        requestRepository.deleteExpired(now.minus(Duration.ofHours(1)));
        grantRepository.deleteUnexchanged(now.minus(Duration.ofHours(1)));

        OAuthClient client = Optional.ofNullable(params.get("client_id"))
                .flatMap(clientRepository::findByClientId)
                .orElse(null);
        if (client == null) {
            return consentError("invalid_request", "This app is not registered with Railhook. Reconnect it from the app.");
        }
        List<String> registered = client.redirectUriList();
        String redirectUri = params.get("redirect_uri");
        if (redirectUri == null && registered.size() == 1) {
            redirectUri = registered.get(0);
        }
        if (!RedirectUris.isRegistered(redirectUri, registered)) {
            return consentError("invalid_request", "The app asked to be sent somewhere it did not register.");
        }

        String state = params.get("state");
        if (!"code".equals(params.get("response_type"))) {
            return appRedirect(redirectUri, "unsupported_response_type", "Only response_type=code is supported", state);
        }
        String challenge = params.get("code_challenge");
        if (challenge == null) {
            return appRedirect(redirectUri, "invalid_request", "code_challenge is required: PKCE is mandatory", state);
        }
        if (!"S256".equals(params.get("code_challenge_method")) || !OAuthSecrets.isS256Challenge(challenge)) {
            return appRedirect(redirectUri, "invalid_request", "code_challenge_method must be S256", state);
        }
        String resource = params.get("resource");
        if (resource != null && !settings.isThisResource(resource)) {
            return appRedirect(redirectUri, "invalid_target", "resource must be " + settings.resource(), state);
        }
        if (state != null && state.length() > 1000) {
            return appRedirect(redirectUri, "invalid_request", "state is too long", null);
        }
        String scope = params.get("scope");
        if (scope != null && scope.length() > 500) {
            return appRedirect(redirectUri, "invalid_scope", "scope is too long", state);
        }

        OAuthAuthorizationRequest request = requestRepository.save(OAuthAuthorizationRequest.builder()
                .clientId(client.getId())
                .redirectUri(redirectUri)
                .codeChallenge(challenge)
                .state(state)
                .requestedScope(scope)
                .resource(resource)
                .expiresAt(now.plus(McpOAuthSettings.REQUEST_LIFETIME))
                .build());
        return settings.consentPage() + "?request=" + request.getId();
    }

    // ── consent (the signed-in person, from the dashboard) ───────────────

    @Transactional(readOnly = true)
    public McpConsentRequestResponse describeRequest(UUID requestId, UUID userId) {
        OAuthAuthorizationRequest request = requestRepository.findById(requestId)
                .filter(this::isOpen)
                .orElseThrow(McpOAuthService::requestGone);
        OAuthClient client = clientRepository.findById(request.getClientId()).orElseThrow(McpOAuthService::requestGone);
        Membership membership = activeMembership(userId);
        return McpConsentRequestResponse.builder()
                .requestId(request.getId())
                .clientName(client.getClientName())
                .clientUri(client.getClientUri())
                .redirectHost(RedirectUris.hostOf(request.getRedirectUri()))
                .requestedScope(scopeFromRequest(request.getRequestedScope()))
                .canGrantWrite(WRITER_ROLES.contains(membership.getRole()))
                .expiresAt(request.getExpiresAt())
                .build();
    }

    /**
     * Approves a request for one project of the caller's organization, and hands back where to send
     * the browser with the code. The project is looked up in the caller's tenant, so a project of
     * another organization is simply not found.
     */
    @Transactional
    public McpConsentDecisionResponse approve(UUID requestId, UUID userId, McpConsentApproveRequest decision) {
        OAuthAuthorizationRequest request = requestRepository.findForUpdate(requestId)
                .filter(this::isOpen)
                .orElseThrow(McpOAuthService::requestGone);
        OAuthClient client = clientRepository.findById(request.getClientId()).orElseThrow(McpOAuthService::requestGone);
        projectRepository.findById(decision.getProjectId())
                .orElseThrow(() -> new NotFoundException("Project not found"));
        Membership membership = activeMembership(userId);
        if (decision.getScope() == ApiKeyScope.READ_WRITE && !WRITER_ROLES.contains(membership.getRole())) {
            throw new ForbiddenException("Your role can connect an app with read-only access. "
                    + "An owner or developer can connect it with write access.");
        }

        Instant now = now();
        request.setCompletedAt(now);
        requestRepository.save(request);

        String code = OAuthSecrets.mint(OAuthSecrets.CODE_PREFIX);
        OAuthGrant grant = grantRepository.save(OAuthGrant.builder()
                .organizationId(TenantContext.require())
                .projectId(decision.getProjectId())
                .clientId(client.getId())
                .userId(userId)
                .scope(decision.getScope())
                .redirectUri(request.getRedirectUri())
                .codeChallenge(request.getCodeChallenge())
                .codeHash(OAuthSecrets.hash(code))
                .codeExpiresAt(now.plus(McpOAuthSettings.CODE_LIFETIME))
                .build());

        audit(AuditAction.CREATE, userId, grant, client, null);
        log.info("MCP app {} connected to project {} with {} by user {}", client.getClientName(),
                grant.getProjectId(), grant.getScope(), userId);

        Map<String, String> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("state", request.getState());
        response.put("iss", settings.issuer());
        return new McpConsentDecisionResponse(withQuery(request.getRedirectUri(), response));
    }

    @Transactional
    public McpConsentDecisionResponse deny(UUID requestId) {
        OAuthAuthorizationRequest request = requestRepository.findForUpdate(requestId)
                .filter(this::isOpen)
                .orElseThrow(McpOAuthService::requestGone);
        request.setCompletedAt(now());
        requestRepository.save(request);
        return new McpConsentDecisionResponse(appRedirect(request.getRedirectUri(), "access_denied",
                "The person declined to connect this app", request.getState()));
    }

    // ── token endpoint ───────────────────────────────────────────────────

    /**
     * Exchanges a code or a refresh token for a new token pair.
     *
     * <p>{@code noRollbackFor}, because two of its refusals are also writes that must stick: a
     * replayed code and a replayed refresh token each revoke the grant they belong to.
     */
    @SystemTenant("called by the app with only its client credentials -- the grant it names decides the organization")
    @Transactional(noRollbackFor = OAuthProtocolException.class)
    public Map<String, Object> token(Map<String, String> params, String authorization) {
        String grantType = params.get("grant_type");
        if (grantType == null) {
            throw OAuthProtocolException.invalidRequest("grant_type is required");
        }
        OAuthClient client = authenticateClient(params, authorization);
        return switch (grantType) {
            case "authorization_code" -> exchangeCode(client, params);
            case "refresh_token" -> refresh(client, params);
            default -> throw new OAuthProtocolException("unsupported_grant_type",
                    "grant_type must be authorization_code or refresh_token");
        };
    }

    private Map<String, Object> exchangeCode(OAuthClient client, Map<String, String> params) {
        String code = required(params, "code");
        OAuthGrant grant = grantRepository.findByCodeHash(OAuthSecrets.hash(code))
                .filter(g -> g.getClientId().equals(client.getId()))
                .orElseThrow(() -> OAuthProtocolException.invalidGrant("The code is not valid"));
        if (grant.getCodeUsedAt() != null) {
            // OAuth 2.1 §4.1.3: a code presented twice means someone else has it too. Whatever it
            // was exchanged for goes with it.
            revoke(grant, null, "authorization code presented twice");
            throw OAuthProtocolException.invalidGrant("The code was already used");
        }
        if (grant.getRevokedAt() != null || grant.getCodeExpiresAt().isBefore(now())) {
            throw OAuthProtocolException.invalidGrant("The code has expired");
        }
        String redirectUri = params.get("redirect_uri");
        if (redirectUri != null && !redirectUri.equals(grant.getRedirectUri())) {
            throw OAuthProtocolException.invalidGrant("redirect_uri does not match the authorization request");
        }
        checkResource(params);
        if (!OAuthSecrets.pkceMatches(params.get("code_verifier"), grant.getCodeChallenge())) {
            throw OAuthProtocolException.invalidGrant("code_verifier does not match the code_challenge");
        }
        if (!stillBacked(grant)) {
            throw OAuthProtocolException.invalidGrant("The person who approved this no longer has that access");
        }
        Instant now = now();
        grant.setCodeUsedAt(now);
        grant.setActivatedAt(now);
        return issueTokens(grant);
    }

    private Map<String, Object> refresh(OAuthClient client, Map<String, String> params) {
        String hash = OAuthSecrets.hash(required(params, "refresh_token"));
        Optional<OAuthGrant> current = grantRepository.findByRefreshTokenHash(hash);
        if (current.isEmpty()) {
            // A refresh token that was already rotated away. Only a copy of it could still be
            // presented, so the grant is revoked for its rightful holder too (OAuth 2.1 §4.3.1).
            grantRepository.findByPreviousRefreshTokenHash(hash)
                    .filter(g -> g.getRevokedAt() == null)
                    .ifPresent(g -> revoke(g, null, "rotated refresh token presented again"));
            throw OAuthProtocolException.invalidGrant("The refresh token is not valid");
        }
        OAuthGrant grant = current.get();
        if (!grant.getClientId().equals(client.getId())) {
            throw OAuthProtocolException.invalidGrant("The refresh token is not valid");
        }
        if (grant.getRevokedAt() != null || grant.getRefreshTokenExpiresAt().isBefore(now())) {
            throw OAuthProtocolException.invalidGrant("The refresh token has expired or was revoked");
        }
        checkResource(params);
        if (!stillBacked(grant)) {
            throw OAuthProtocolException.invalidGrant("The person who approved this no longer has that access");
        }
        grant.setPreviousRefreshTokenHash(grant.getRefreshTokenHash());
        return issueTokens(grant);
    }

    private Map<String, Object> issueTokens(OAuthGrant grant) {
        Instant now = now();
        String accessToken = OAuthSecrets.mint(OAuthSecrets.ACCESS_TOKEN_PREFIX);
        String refreshToken = OAuthSecrets.mint(OAuthSecrets.REFRESH_TOKEN_PREFIX);
        grant.setAccessTokenHash(OAuthSecrets.hash(accessToken));
        grant.setAccessTokenExpiresAt(now.plus(McpOAuthSettings.ACCESS_TOKEN_LIFETIME));
        grant.setRefreshTokenHash(OAuthSecrets.hash(refreshToken));
        grant.setRefreshTokenExpiresAt(now.plus(McpOAuthSettings.REFRESH_TOKEN_LIFETIME));
        grantRepository.save(grant);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", accessToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", McpOAuthSettings.ACCESS_TOKEN_LIFETIME.toSeconds());
        response.put("refresh_token", refreshToken);
        response.put("scope", scopeString(grant.getScope()));
        return response;
    }

    // ── revocation endpoint (RFC 7009) ───────────────────────────────────

    /**
     * Revokes the grant a token belongs to, when that token is this client's. Either token ends the
     * whole grant: an app asking to be disconnected means all of it. An unknown token is not an
     * error (§2.2), so the answer does not tell a caller which tokens exist.
     */
    @SystemTenant("called by the app with only its client credentials -- the token decides the organization")
    @Transactional
    public void revokeToken(Map<String, String> params, String authorization) {
        OAuthClient client = authenticateClient(params, authorization);
        String hash = OAuthSecrets.hash(required(params, "token"));
        grantRepository.findByRefreshTokenHash(hash)
                .or(() -> grantRepository.findByAccessTokenHash(hash))
                .filter(g -> g.getClientId().equals(client.getId()) && g.getRevokedAt() == null)
                .ifPresent(g -> revoke(g, null, "revoked by the app"));
    }

    // ── the resource server: who is calling /mcp ─────────────────────────

    /**
     * The caller an access token stands for, or empty when it stands for nobody: unknown, expired,
     * revoked, or approved by someone who no longer has the access it grants.
     */
    @SystemTenant("authentication precedes tenancy: the grant row is what names the organization")
    @Transactional
    public Optional<McpOAuthAuthenticationToken> authenticate(String accessToken) {
        if (!settings.enabled()) {
            return Optional.empty();
        }
        Instant now = now();
        Optional<OAuthGrant> found = grantRepository.findByAccessTokenHash(OAuthSecrets.hash(accessToken))
                .filter(g -> g.getRevokedAt() == null && g.getActivatedAt() != null)
                .filter(g -> g.getAccessTokenExpiresAt() != null && g.getAccessTokenExpiresAt().isAfter(now))
                .filter(this::stillBacked);
        found.filter(g -> g.getLastUsedAt() == null || g.getLastUsedAt().isBefore(now.minus(TOUCH_INTERVAL)))
                .ifPresent(g -> grantRepository.touch(g.getId(), now));
        return found.map(g -> new McpOAuthAuthenticationToken(g.getId(), g.getProjectId(), g.getOrganizationId(),
                g.getScope()));
    }

    // ── project settings ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<McpGrantResponse> listGrants(UUID projectId) {
        projectRepository.findById(projectId).orElseThrow(() -> new NotFoundException("Project not found"));
        List<OAuthGrant> grants = grantRepository
                .findByProjectIdAndActivatedAtIsNotNullAndRevokedAtIsNullOrderByCreatedAtDesc(projectId);
        Map<UUID, OAuthClient> clients = clientRepository.findAllById(
                        grants.stream().map(OAuthGrant::getClientId).distinct().toList()).stream()
                .collect(Collectors.toMap(OAuthClient::getId, Function.identity()));
        Map<UUID, String> emails = userRepository.findAllById(
                        grants.stream().map(OAuthGrant::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));

        List<McpGrantResponse> out = new ArrayList<>();
        for (OAuthGrant grant : grants) {
            OAuthClient client = clients.get(grant.getClientId());
            out.add(McpGrantResponse.builder()
                    .id(grant.getId())
                    .projectId(grant.getProjectId())
                    .clientName(client == null ? "MCP app" : client.getClientName())
                    .clientUri(client == null ? null : client.getClientUri())
                    .redirectHost(RedirectUris.hostOf(grant.getRedirectUri()))
                    .scope(grant.getScope())
                    .approvedByEmail(emails.get(grant.getUserId()))
                    .createdAt(grant.getActivatedAt())
                    .lastUsedAt(grant.getLastUsedAt())
                    .build());
        }
        return out;
    }

    @Transactional
    public void revokeGrant(UUID projectId, UUID grantId, UUID userId) {
        projectRepository.findById(projectId).orElseThrow(() -> new NotFoundException("Project not found"));
        OAuthGrant grant = grantRepository.findByIdAndProjectId(grantId, projectId)
                .filter(g -> g.getRevokedAt() == null)
                .orElseThrow(() -> new NotFoundException("Connected app not found"));
        revoke(grant, userId, "revoked from project settings");
    }

    // ── internals ────────────────────────────────────────────────────────

    /**
     * Who is calling the token or revocation endpoint. A public client names itself; a client that
     * registered a secret must present it, in the body or as HTTP Basic (RFC 6749 §2.3.1).
     */
    private OAuthClient authenticateClient(Map<String, String> params, String authorization) {
        String clientId = params.get("client_id");
        String secret = params.get("client_secret");
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw OAuthProtocolException.invalidClient("Malformed Basic credentials");
            }
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                throw OAuthProtocolException.invalidClient("Malformed Basic credentials");
            }
            String basicId = URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
            if (clientId != null && !clientId.equals(basicId)) {
                throw OAuthProtocolException.invalidRequest("client_id differs between the body and the Authorization header");
            }
            clientId = basicId;
            secret = URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8);
        }
        if (clientId == null) {
            throw OAuthProtocolException.invalidClient("client_id is required");
        }
        OAuthClient client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> OAuthProtocolException.invalidClient("Unknown client"));
        if (client.getClientSecretHash() != null && !OAuthSecrets.matches(secret, client.getClientSecretHash())) {
            throw OAuthProtocolException.invalidClient("Client authentication failed");
        }
        return client;
    }

    /**
     * Whether the person behind a grant could still approve it today: an active member of its
     * organization and, for READ_WRITE, in a role that may create an API key.
     */
    private boolean stillBacked(OAuthGrant grant) {
        return membershipRepository.findByUserIdAndOrganizationId(grant.getUserId(), grant.getOrganizationId())
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .filter(m -> grant.getScope() == ApiKeyScope.READ_ONLY || WRITER_ROLES.contains(m.getRole()))
                .isPresent();
    }

    private Membership activeMembership(UUID userId) {
        return membershipRepository.findByUserIdAndOrganizationId(userId, TenantContext.require())
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ForbiddenException("You are not an active member of this organization"));
    }

    private void revoke(OAuthGrant grant, UUID userId, String reason) {
        grant.setRevokedAt(now());
        grantRepository.save(grant);
        OAuthClient client = clientRepository.findById(grant.getClientId()).orElse(null);
        audit(AuditAction.REVOKE, userId, grant, client, reason);
        log.info("MCP grant {} for project {} revoked: {}", grant.getId(), grant.getProjectId(), reason);
    }

    private void audit(AuditAction action, UUID userId, OAuthGrant grant, OAuthClient client, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("grantId", grant.getId());
        details.put("projectId", grant.getProjectId());
        details.put("client", client == null ? null : client.getClientName());
        details.put("redirectHost", RedirectUris.hostOf(grant.getRedirectUri()));
        details.put("scope", grant.getScope());
        if (reason != null) {
            details.put("reason", reason);
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            json = null;
        }
        auditLog.record(action, RESOURCE_TYPE, userId, grant.getOrganizationId(), "SUCCESS", null, json);
    }

    private void checkResource(Map<String, String> params) {
        String resource = params.get("resource");
        if (resource != null && !settings.isThisResource(resource)) {
            throw new OAuthProtocolException("invalid_target", "resource must be " + settings.resource());
        }
    }

    private boolean isOpen(OAuthAuthorizationRequest request) {
        return request.getCompletedAt() == null && request.getExpiresAt().isAfter(now());
    }

    private static NotFoundException requestGone() {
        return new NotFoundException("This request from the app has expired or was already answered. "
                + "Start again from the app.");
    }

    /** READ_WRITE when the app asked for mcp:write, READ_ONLY otherwise: least privilege by default. */
    static ApiKeyScope scopeFromRequest(String scope) {
        if (scope == null) {
            return ApiKeyScope.READ_ONLY;
        }
        for (String part : scope.trim().split("\\s+")) {
            if (part.equals(McpOAuthSettings.SCOPE_WRITE)) {
                return ApiKeyScope.READ_WRITE;
            }
        }
        return ApiKeyScope.READ_ONLY;
    }

    static String scopeString(ApiKeyScope scope) {
        return scope == ApiKeyScope.READ_WRITE
                ? McpOAuthSettings.SCOPE_READ + " " + McpOAuthSettings.SCOPE_WRITE
                : McpOAuthSettings.SCOPE_READ;
    }

    private String consentError(String error, String description) {
        return settings.consentPage() + "?error=" + encode(error) + "&error_description=" + encode(description);
    }

    private String appRedirect(String redirectUri, String error, String description, String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("error", error);
        params.put("error_description", description);
        params.put("state", state);
        params.put("iss", settings.issuer());
        return withQuery(redirectUri, params);
    }

    private static String withQuery(String uri, Map<String, String> params) {
        StringBuilder out = new StringBuilder(uri);
        boolean hasQuery = URI.create(uri).getRawQuery() != null;
        for (Map.Entry<String, String> param : params.entrySet()) {
            if (param.getValue() == null) {
                continue;
            }
            out.append(hasQuery ? '&' : '?').append(param.getKey()).append('=').append(encode(param.getValue()));
            hasQuery = true;
        }
        return out.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String required(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            throw OAuthProtocolException.invalidRequest(name + " is required");
        }
        return value;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s)) {
                throw new OAuthProtocolException("invalid_client_metadata", "Expected an array of strings");
            }
            out.add(s);
        }
        return out;
    }

    private static boolean isHttpsUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** A display name: control characters out, whitespace collapsed, bounded. */
    private static String clean(String value, int max) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private Instant now() {
        return clock.instant();
    }
}
