package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.CryptoUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class EncryptionKeyRotationService {

    private final EndpointRepository endpointRepository;
    private final IncomingSourceRepository incomingSourceRepository;
    private final IncomingDestinationRepository incomingDestinationRepository;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final TransactionTemplate transactionTemplate;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Counter partialFailureCounter;

    private static final int BATCH_SIZE = 100;
    private static final String LOCK_NAME = "encryption-key-rotation";
    private static final Duration LOCK_AT_MOST = Duration.ofHours(1);
    private static final Duration LOCK_AT_LEAST = Duration.ofSeconds(5);

    public EncryptionKeyRotationService(
            EndpointRepository endpointRepository,
            IncomingSourceRepository incomingSourceRepository,
            IncomingDestinationRepository incomingDestinationRepository,
            EncryptionKeyRegistry encryptionKeyRegistry,
            TransactionTemplate transactionTemplate,
            LockingTaskExecutor lockingTaskExecutor,
            MeterRegistry meterRegistry) {
        this.endpointRepository = endpointRepository;
        this.incomingSourceRepository = incomingSourceRepository;
        this.incomingDestinationRepository = incomingDestinationRepository;
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.transactionTemplate = transactionTemplate;
        this.lockingTaskExecutor = lockingTaskExecutor;
        // A partial rotation failure can leave some tenants' secrets encrypted under a
        // key version other records no longer carry — that must never be silently tolerated.
        // This counter is the alertable signal (paired with a non-200 response to the caller).
        this.partialFailureCounter = Counter.builder("encryption_rotation_partial_failures_total")
                .description("Count of individual secret re-encryption failures during a key rotation run")
                .register(meterRegistry);
    }

    public RotationResult rotateAll() {
        AtomicReference<RotationResult> resultRef = new AtomicReference<>();

        LockConfiguration lockConfig = new LockConfiguration(
                Instant.now(), LOCK_NAME, LOCK_AT_MOST, LOCK_AT_LEAST);

        lockingTaskExecutor.executeWithLock(
                (Runnable) () -> resultRef.set(doRotateAll()),
                lockConfig
        );

        RotationResult result = resultRef.get();
        if (result == null) {
            log.warn("Could not acquire lock for encryption key rotation — another node is already running it");
            throw new IllegalStateException(
                    "Encryption key rotation is already in progress on another node. Try again later.");
        }
        return result;
    }

    private RotationResult doRotateAll() {
        int activeVersion = encryptionKeyRegistry.getActiveVersion();
        log.info("Starting encryption key rotation to version {}", activeVersion);

        AtomicInteger endpointsRotated = new AtomicInteger();
        AtomicInteger sourcesRotated = new AtomicInteger();
        AtomicInteger destinationsRotated = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        rotateEndpoints(activeVersion, endpointsRotated, errors);
        rotateIncomingSources(activeVersion, sourcesRotated, errors);
        rotateIncomingDestinations(activeVersion, destinationsRotated, errors);

        RotationResult result = new RotationResult(
                activeVersion,
                endpointsRotated.get(),
                sourcesRotated.get(),
                destinationsRotated.get(),
                errors.get()
        );

        if (result.errors() > 0) {
            partialFailureCounter.increment(result.errors());
            log.error("Encryption key rotation completed with {} error(s) — some secrets were NOT "
                    + "re-encrypted to version {} and may become undecryptable if the old key is "
                    + "ever retired: {}", result.errors(), activeVersion, result);
        } else {
            log.info("Encryption key rotation completed: {}", result);
        }
        return result;
    }

    private void rotateEndpoints(int targetVersion, AtomicInteger rotated, AtomicInteger errors) {
        int page = 0;
        while (true) {
            Page<Endpoint> batch = endpointRepository.findAll(PageRequest.of(page, BATCH_SIZE));
            if (batch.isEmpty()) break;

            for (Endpoint endpoint : batch) {
                if (endpoint.getEncryptionKeyVersion() != null
                        && endpoint.getEncryptionKeyVersion() == targetVersion) {
                    continue;
                }
                try {
                    transactionTemplate.executeWithoutResult(status -> rotateEndpoint(endpoint, targetVersion));
                    rotated.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                    log.error("Failed to rotate endpoint {}: {}", endpoint.getId(), e.getMessage());
                }
            }

            if (!batch.hasNext()) break;
            page++;
        }
    }

    private void rotateEndpoint(Endpoint endpoint, int targetVersion) {
        int currentVersion = endpoint.getEncryptionKeyVersion() != null ? endpoint.getEncryptionKeyVersion() : 1;

        reEncrypt(currentVersion, endpoint::getSecretEncrypted, endpoint::getSecretIv,
                endpoint::setSecretEncrypted, endpoint::setSecretIv);
        reEncrypt(currentVersion, endpoint::getClientCertEncrypted, endpoint::getClientCertIv,
                endpoint::setClientCertEncrypted, endpoint::setClientCertIv);
        reEncrypt(currentVersion, endpoint::getClientKeyEncrypted, endpoint::getClientKeyIv,
                endpoint::setClientKeyEncrypted, endpoint::setClientKeyIv);
        reEncrypt(currentVersion, endpoint::getSecretPreviousEncrypted, endpoint::getSecretPreviousIv,
                endpoint::setSecretPreviousEncrypted, endpoint::setSecretPreviousIv);

        endpoint.setEncryptionKeyVersion(targetVersion);
        endpointRepository.save(endpoint);
    }

    /**
     * Decrypts one field under the key it was written with and writes it back under the current
     * one. A field that is not set stays that way — an endpoint without mTLS has no certificate
     * to rotate.
     */
    private void reEncrypt(int currentVersion, Supplier<String> ciphertext, Supplier<String> iv,
            Consumer<String> setCiphertext, Consumer<String> setIv) {
        if (ciphertext.get() == null || iv.get() == null) {
            return;
        }
        String plaintext = encryptionKeyRegistry.decryptWithFallback(ciphertext.get(), iv.get(), currentVersion);
        CryptoUtils.EncryptedData reEncrypted = encryptionKeyRegistry.encrypt(plaintext);
        setCiphertext.accept(reEncrypted.getCiphertext());
        setIv.accept(reEncrypted.getIv());
    }

    private void rotateIncomingSources(int targetVersion, AtomicInteger rotated, AtomicInteger errors) {
        int page = 0;
        while (true) {
            Page<IncomingSource> batch = incomingSourceRepository.findAll(PageRequest.of(page, BATCH_SIZE));
            if (batch.isEmpty()) break;

            for (IncomingSource source : batch) {
                if (source.getEncryptionKeyVersion() != null
                        && source.getEncryptionKeyVersion() == targetVersion) {
                    continue;
                }
                if (source.getHmacSecretEncrypted() == null) {
                    continue;
                }
                try {
                    transactionTemplate.executeWithoutResult(status -> rotateIncomingSource(source, targetVersion));
                    rotated.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                    log.error("Failed to rotate incoming source {}: {}", source.getId(), e.getMessage());
                }
            }

            if (!batch.hasNext()) break;
            page++;
        }
    }

    private void rotateIncomingSource(IncomingSource source, int targetVersion) {
        int currentVersion = source.getEncryptionKeyVersion() != null ? source.getEncryptionKeyVersion() : 1;

        reEncrypt(currentVersion, source::getHmacSecretEncrypted, source::getHmacSecretIv,
                source::setHmacSecretEncrypted, source::setHmacSecretIv);
        source.setEncryptionKeyVersion(targetVersion);
        incomingSourceRepository.save(source);
    }

    private void rotateIncomingDestinations(int targetVersion, AtomicInteger rotated, AtomicInteger errors) {
        int page = 0;
        while (true) {
            Page<IncomingDestination> batch = incomingDestinationRepository.findAll(PageRequest.of(page, BATCH_SIZE));
            if (batch.isEmpty()) break;

            for (IncomingDestination dest : batch) {
                if (dest.getEncryptionKeyVersion() != null
                        && dest.getEncryptionKeyVersion() == targetVersion) {
                    continue;
                }
                if (dest.getAuthConfigEncrypted() == null) {
                    continue;
                }
                try {
                    transactionTemplate.executeWithoutResult(status -> rotateIncomingDestination(dest, targetVersion));
                    rotated.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                    log.error("Failed to rotate incoming destination {}: {}", dest.getId(), e.getMessage());
                }
            }

            if (!batch.hasNext()) break;
            page++;
        }
    }

    private void rotateIncomingDestination(IncomingDestination dest, int targetVersion) {
        int currentVersion = dest.getEncryptionKeyVersion() != null ? dest.getEncryptionKeyVersion() : 1;

        reEncrypt(currentVersion, dest::getAuthConfigEncrypted, dest::getAuthConfigIv,
                dest::setAuthConfigEncrypted, dest::setAuthConfigIv);
        dest.setEncryptionKeyVersion(targetVersion);
        incomingDestinationRepository.save(dest);
    }

    public record RotationResult(
            int targetVersion,
            int endpointsRotated,
            int sourcesRotated,
            int destinationsRotated,
            int errors
    ) {}
}
