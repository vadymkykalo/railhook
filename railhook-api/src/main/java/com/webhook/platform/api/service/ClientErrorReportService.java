package com.webhook.platform.api.service;

import com.webhook.platform.api.dto.ClientErrorReportRequest;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Browser-chosen text: control characters are stripped against forged log lines. The in-memory
 * throttle protects log volume, not a security boundary.
 */
@Service
@Slf4j
public class ClientErrorReportService {

    private static final int MAX_MESSAGE = 500;
    private static final int MAX_STACK = 2000;
    private static final int MAX_COMPONENT_STACK = 2000;
    private static final int MAX_URL = 300;
    private static final int MAX_RELEASE = 60;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final boolean enabled;
    private final int reportsPerUserPerMinute;
    // Bounded and expiring: a plain map kept one entry per user for the life of the process.
    private final Cache<UUID, Window> windows = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(WINDOW.multipliedBy(2))
            .build();

    public ClientErrorReportService(
            @Value("${client-errors.enabled:true}") boolean enabled,
            @Value("${client-errors.per-user-per-minute:20}") int reportsPerUserPerMinute) {
        this.enabled = enabled;
        this.reportsPerUserPerMinute = reportsPerUserPerMinute;
    }

    /** Never throws; the endpoint answers 202 whether or not the report was kept. */
    public void record(ClientErrorReportRequest report, UUID userId) {
        if (!enabled) {
            return;
        }
        String message = clean(report.getMessage(), MAX_MESSAGE);
        if (message.isBlank()) {
            return;
        }
        if (!admit(userId)) {
            return;
        }

        log.warn("Dashboard error [org={} user={} release={}] at {}: {}{}{}",
                TenantContext.current(), userId,
                clean(report.getRelease(), MAX_RELEASE),
                pathOf(report.getUrl()),
                message,
                suffix(" | stack: ", clean(report.getStack(), MAX_STACK)),
                suffix(" | component: ", clean(report.getComponentStack(), MAX_COMPONENT_STACK)));
    }

    // For tests.
    long trackedWindows() {
        windows.cleanUp();
        return windows.estimatedSize();
    }

    private boolean admit(UUID userId) {
        if (userId == null) {
            return true;
        }
        Instant now = Instant.now();
        Window window = windows.asMap().compute(userId, (key, existing) ->
                existing == null || existing.startedBefore(now.minus(WINDOW))
                        ? new Window(now)
                        : existing);
        return window.count.incrementAndGet() <= reportsPerUserPerMinute;
    }

    // The query string can carry share tokens or customer data, so only the path is logged.
    private static String pathOf(String url) {
        String cleaned = clean(url, MAX_URL);
        int query = cleaned.indexOf('?');
        String withoutQuery = query >= 0 ? cleaned.substring(0, query) : cleaned;
        int fragment = withoutQuery.indexOf('#');
        return fragment >= 0 ? withoutQuery.substring(0, fragment) : withoutQuery;
    }

    // Control characters become a single space rather than vanishing, so a stack trace stays readable.
    private static String clean(String value, int max) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), max));
        boolean lastWasSpace = false;
        for (int i = 0; i < value.length() && out.length() < max; i++) {
            char c = value.charAt(i);
            boolean printable = c >= 0x20 && c != 0x7F;
            if (printable) {
                out.append(c);
                lastWasSpace = c == ' ';
            } else if (!lastWasSpace) {
                out.append(' ');
                lastWasSpace = true;
            }
        }
        String result = out.toString().trim();
        return value.length() > max ? result + "…" : result;
    }

    private static String suffix(String label, String value) {
        return value.isBlank() ? "" : label + value;
    }

    private static final class Window {
        private final Instant startedAt;
        private final AtomicInteger count = new AtomicInteger();

        private Window(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private boolean startedBefore(Instant cutoff) {
            return startedAt.isBefore(cutoff);
        }
    }
}
