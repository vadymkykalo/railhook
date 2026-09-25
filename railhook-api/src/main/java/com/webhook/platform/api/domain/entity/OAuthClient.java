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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Not tenant-scoped: an app registers before any organization is involved, and one registration
 * serves everyone who connects it. Access is decided per {@link OAuthGrant}.
 */
@Entity
@Table(name = "oauth_clients")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthClient {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id", nullable = false, unique = true, length = 64)
    private String clientId;

    /** Null for a public client, which proves itself with PKCE alone. */
    @Column(name = "client_secret_hash", length = 64)
    private String clientSecretHash;

    @Column(name = "token_endpoint_auth_method", nullable = false, length = 32)
    private String tokenEndpointAuthMethod;

    @Column(name = "client_name", nullable = false, length = 200)
    private String clientName;

    @Column(name = "client_uri", length = 2000)
    private String clientUri;

    /** One absolute URI per line, matched exactly. */
    @Column(name = "redirect_uris", nullable = false, columnDefinition = "TEXT")
    private String redirectUris;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public List<String> redirectUriList() {
        return Arrays.stream(redirectUris.split("\n")).filter(s -> !s.isBlank()).toList();
    }
}
