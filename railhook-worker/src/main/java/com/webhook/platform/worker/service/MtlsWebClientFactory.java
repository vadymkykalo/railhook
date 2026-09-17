package com.webhook.platform.worker.service;

import com.webhook.platform.common.http.SsrfProtectionCustomizer;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.CryptoUtils;
import com.webhook.platform.worker.domain.entity.Endpoint;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Base64;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
public class MtlsWebClientFactory {

    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final boolean allowPrivateIps;
    private final java.util.List<String> allowedHosts;
    private final WebClient.Builder webClientBuilder;
    private final ConnectionProvider connectionProvider;
    private final Cache<UUID, CachedClient> mtlsClientCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    private record CachedClient(WebClient webClient, Instant updatedAt) {}

    public MtlsWebClientFactory(
            EncryptionKeyRegistry encryptionKeyRegistry,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") java.util.List<String> allowedHosts,
            WebClient.Builder webClientBuilder,
            ConnectionProvider webhookConnectionProvider) {
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.allowPrivateIps = allowPrivateIps;
        this.allowedHosts = allowedHosts;
        this.webClientBuilder = webClientBuilder;
        this.connectionProvider = webhookConnectionProvider;
    }

    public WebClient getWebClient(Endpoint endpoint) {
        if (!Boolean.TRUE.equals(endpoint.getMtlsEnabled())) {
            return webClientBuilder.build();
        }

        Instant endpointUpdatedAt = endpoint.getUpdatedAt();

        // One compute per endpoint, so callers that miss the cache together wait for a single
        // build instead of each building a client and the last put deciding which one stays.
        return mtlsClientCache.asMap().compute(endpoint.getId(), (id, cached) -> {
            if (cached != null && !isStale(cached, endpointUpdatedAt)) {
                return cached;
            }
            if (cached != null) {
                log.info("mTLS config changed for endpoint {}, replacing cached client", id);
            }
            try {
                return new CachedClient(createMtlsWebClient(endpoint), endpointUpdatedAt);
            } catch (Exception e) {
                log.error("Failed to create mTLS WebClient for endpoint {}: {}", id, e.getMessage());
                throw new RuntimeException("Failed to create mTLS client", e);
            }
        }).webClient();
    }

    private static boolean isStale(CachedClient cached, Instant endpointUpdatedAt) {
        return endpointUpdatedAt != null
                && cached.updatedAt() != null
                && endpointUpdatedAt.isAfter(cached.updatedAt());
    }

    public void invalidateCache(UUID endpointId) {
        mtlsClientCache.invalidate(endpointId);
        log.debug("Invalidated mTLS WebClient cache for endpoint {}", endpointId);
    }

    private WebClient createMtlsWebClient(Endpoint endpoint) throws Exception {
        String clientCert = encryptionKeyRegistry.decryptWithFallback(
                endpoint.getClientCertEncrypted(),
                endpoint.getClientCertIv(),
                endpoint.getEncryptionKeyVersion()
        );
        String clientKey = encryptionKeyRegistry.decryptWithFallback(
                endpoint.getClientKeyEncrypted(),
                endpoint.getClientKeyIv(),
                endpoint.getEncryptionKeyVersion()
        );

        X509Certificate certificate = loadCertificate(clientCert);
        PrivateKey privateKey = loadPrivateKey(clientKey);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("client", privateKey, new char[0], new X509Certificate[]{certificate});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);

        SslContextBuilder sslContextBuilder = SslContextBuilder.forClient()
                .keyManager(kmf);

        if (endpoint.getCaCert() != null && !endpoint.getCaCert().isEmpty()) {
            X509Certificate caCertificate = loadCertificate(endpoint.getCaCert());
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            trustStore.load(null, null);
            trustStore.setCertificateEntry("ca", caCertificate);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            sslContextBuilder.trustManager(tmf);
        }

        SslContext sslContext = sslContextBuilder.build();

        HttpClient httpClient = SsrfProtectionCustomizer.apply(
                HttpClient.create(connectionProvider), allowPrivateIps, allowedHosts)
                .secure(spec -> spec.sslContext(sslContext));

        log.info("Created mTLS WebClient for endpoint {}", endpoint.getId());

        // A copy: the connector holds this endpoint's certificate, and configured on the injected
        // builder it leaked into whichever client was built from it next.
        return webClientBuilder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    private X509Certificate loadCertificate(String pem) throws Exception {
        String certContent = pem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");

        byte[] decoded = Base64.getDecoder().decode(certContent);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(decoded));
    }

    private PrivateKey loadPrivateKey(String pem) throws Exception {
        String keyContent = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] decoded = Base64.getDecoder().decode(keyContent);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
        
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(keySpec);
        } catch (Exception e) {
            KeyFactory kf = KeyFactory.getInstance("EC");
            return kf.generatePrivate(keySpec);
        }
    }
}
