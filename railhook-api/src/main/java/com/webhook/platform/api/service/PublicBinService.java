package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.PublicBin;
import com.webhook.platform.api.domain.entity.PublicBinRequest;
import com.webhook.platform.api.domain.repository.PublicBinRepository;
import com.webhook.platform.api.domain.repository.PublicBinRequestRepository;
import com.webhook.platform.api.dto.PublicBinResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.ingress.HeaderSanitizer;
import com.webhook.platform.api.tenancy.SystemTenant;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The webhook tester on the public site: URLs made without an account that record what is sent
 * to them for a day.
 *
 * <p>Nothing here is tenant data — there is no organization behind a public URL — so every
 * method runs in the system scope. The bounds are what keep an anonymous, public store from
 * being one worth abusing, and each closes a different way in:
 * <ul>
 *   <li>per URL: the latest {@value #KEEP_REQUESTS} requests within {@value #BUDGET_BYTES} bytes of
 *       bodies, each body cut at {@value #MAX_BODY_CHARS} characters — so one URL is at most a
 *       megabyte however hard it is fed;</li>
 *   <li>per address: at most {@code perAddress} live URLs, on top of the per-minute rate limit
 *       and the challenge the controller asks for — a limit per minute alone still lets one
 *       address hold thousands;</li>
 *   <li>overall: at most {@code maxActive} live URLs, so even many addresses cannot grow the
 *       tables past a known size — the tester says it is busy instead;</li>
 *   <li>and a day's life, with credentials masked at the write, since anyone with the URL can
 *       read it.</li>
 * </ul>
 */
@Slf4j
@Service
public class PublicBinService {

    static final int KEEP_REQUESTS = 100;
    static final int MAX_BODY_CHARS = 65_536;
    static final long BUDGET_BYTES = 1_048_576;
    static final Duration LIFETIME = Duration.ofDays(1);
    private static final int SLUG_LENGTH = 24;
    private static final String SLUG_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PublicBinRepository binRepository;
    private final PublicBinRequestRepository requestRepository;
    private final TrustedProxyResolver trustedProxyResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String appBaseUrl;
    private final boolean enabled;
    private final int perAddress;
    private final long maxActive;

    public PublicBinService(PublicBinRepository binRepository,
                            PublicBinRequestRepository requestRepository,
                            TrustedProxyResolver trustedProxyResolver,
                            ObjectMapper objectMapper,
                            Clock clock,
                            @Value("${app.base-url:http://localhost:5173}") String appBaseUrl,
                            @Value("${public-bin.enabled:false}") boolean enabled,
                            @Value("${public-bin.per-address:3}") int perAddress,
                            @Value("${public-bin.max-active:5000}") long maxActive) {
        this.binRepository = binRepository;
        this.requestRepository = requestRepository;
        this.trustedProxyResolver = trustedProxyResolver;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.appBaseUrl = appBaseUrl.replaceAll("/+$", "");
        this.enabled = enabled;
        this.perAddress = perAddress;
        this.maxActive = maxActive;
    }

    /** Why a URL was not made; the controller turns it into a status and an error code. */
    public static class LimitReached extends RuntimeException {
        private final boolean overall;

        LimitReached(boolean overall, String message) {
            super(message);
            this.overall = overall;
        }

        /** True when the tester as a whole is full, false when it is this address. */
        public boolean isOverall() {
            return overall;
        }
    }

    @SystemTenant("a public tester URL belongs to no organization")
    @Transactional
    public PublicBinResponse create(String creatorIp) {
        requireEnabled();
        Instant now = Instant.now(clock);
        if (binRepository.countByExpiresAtAfter(now) >= maxActive) {
            throw new LimitReached(true, "The webhook tester is busy. Try again later.");
        }
        if (creatorIp != null && binRepository.countByCreatorIpAndExpiresAtAfter(creatorIp, now) >= perAddress) {
            throw new LimitReached(false,
                    "Your address already has " + perAddress + " live tester URLs. Use one of them or wait for one to expire.");
        }
        PublicBin bin = binRepository.save(PublicBin.builder()
                .id(UUID.randomUUID())
                .slug(newSlug())
                .expiresAt(now.plus(LIFETIME))
                .creatorIp(creatorIp)
                .build());
        return toResponse(bin, List.of());
    }

    @SystemTenant("a public tester URL belongs to no organization")
    @Transactional
    public long capture(String slug, byte[] rawBody, HttpServletRequest request) {
        PublicBin bin = live(slug);
        String body = null;
        boolean truncated = false;
        if (rawBody != null) {
            body = new String(rawBody, StandardCharsets.UTF_8);
            if (body.length() > MAX_BODY_CHARS) {
                body = body.substring(0, MAX_BODY_CHARS);
                truncated = true;
            }
        }
        PublicBinRequest saved = requestRepository.save(PublicBinRequest.builder()
                .binId(bin.getId())
                .method(request.getMethod())
                .queryString(request.getQueryString())
                .headers(HeaderSanitizer.toJson(request, objectMapper))
                .body(body)
                .bodyTruncated(truncated)
                .sizeBytes(rawBody == null ? 0 : rawBody.length)
                .contentType(request.getContentType())
                .sourceIp(trustedProxyResolver.resolve(request))
                .build());
        binRepository.incrementRequestCount(bin.getId());
        requestRepository.trimToNewest(bin.getId(), KEEP_REQUESTS, BUDGET_BYTES);
        return saved.getId();
    }

    @SystemTenant("a public tester URL belongs to no organization")
    @Transactional(readOnly = true)
    public PublicBinResponse read(String slug) {
        PublicBin bin = live(slug);
        List<PublicBinRequest> requests =
                requestRepository.findByBinIdOrderByIdDesc(bin.getId(), PageRequest.of(0, KEEP_REQUESTS));
        return toResponse(bin, requests);
    }

    @SystemTenant("expired public tester URLs of no organization")
    @Scheduled(fixedRateString = "${public-bin.cleanup-interval-ms:3600000}")
    @SchedulerLock(name = "publicBinCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void deleteExpired() {
        int deleted = binRepository.deleteExpired(Instant.now(clock));
        if (deleted > 0) {
            log.info("Deleted {} expired public tester URLs", deleted);
        }
    }

    /**
     * Off by default: a self-hosted install that never asked for it answers as though the tester
     * did not exist, rather than opening an anonymous store on someone's own server.
     */
    private void requireEnabled() {
        if (!enabled) {
            throw new NotFoundException("The webhook tester is not enabled on this server");
        }
    }

    private PublicBin live(String slug) {
        requireEnabled();
        return binRepository.findBySlugAndExpiresAtAfter(slug, Instant.now(clock))
                .orElseThrow(() -> new NotFoundException("This tester URL does not exist or has expired"));
    }

    private String newSlug() {
        StringBuilder slug = new StringBuilder(SLUG_LENGTH);
        for (int i = 0; i < SLUG_LENGTH; i++) {
            slug.append(SLUG_CHARS.charAt(RANDOM.nextInt(SLUG_CHARS.length())));
        }
        return slug.toString();
    }

    private PublicBinResponse toResponse(PublicBin bin, List<PublicBinRequest> requests) {
        return PublicBinResponse.builder()
                .slug(bin.getSlug())
                .url(appBaseUrl + "/hook/p/" + bin.getSlug())
                .expiresAt(bin.getExpiresAt())
                .requestCount(bin.getRequestCount())
                .requests(requests.stream().map(this::toRequest).toList())
                .build();
    }

    private PublicBinResponse.Request toRequest(PublicBinRequest r) {
        return PublicBinResponse.Request.builder()
                .id(r.getId())
                .method(r.getMethod())
                .query(r.getQueryString())
                .headers(headers(r.getHeaders()))
                .body(r.getBody())
                .bodyTruncated(r.isBodyTruncated())
                .sizeBytes(r.getSizeBytes())
                .contentType(r.getContentType())
                .sourceIp(r.getSourceIp())
                .receivedAt(r.getReceivedAt())
                .build();
    }

    private JsonNode headers(String json) {
        if (json == null) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
