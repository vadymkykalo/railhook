package com.webhook.platform.api.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One browser trip through {@code /oauth/authorize}, parked while a person reads the consent
 * screen. Everything the app asked for is kept here rather than carried through the browser, so
 * the consent screen can only answer the request, never rewrite it.
 *
 * <p>Not tenant-scoped: nobody has signed in when it is created. The person answering it decides
 * the organization, and that lands on the {@link OAuthGrant}.
 */
@Entity
@Table(name = "oauth_authorization_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthAuthorizationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** {@link OAuthClient#getId()}, not the public client_id string. */
    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "redirect_uri", nullable = false, length = 2000)
    private String redirectUri;

    @Column(name = "code_challenge", nullable = false, length = 128)
    private String codeChallenge;

    @Column(name = "state", length = 1000)
    private String state;

    @Column(name = "requested_scope", length = 500)
    private String requestedScope;

    @Column(name = "resource", length = 2000)
    private String resource;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
