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
 * Where a failure in the dashboard ends up.
 *
 * <p>Before this existed, a render error reached {@code console.error} in one person's browser
 * and stopped there: a screen that threw for every customer looked, from here, exactly like a
 * screen nobody had opened. The reports now go into the same logs as everything else — which,
 * with the {@code production} profile finally activating the JSON appender, means Loki, beside
 * the correlation id of whatever request the page was making when it broke. No third party is
 * involved, and nothing leaves the installation; a self-hosted operator reads their own logs.
 *
 * <p>That decision moves the risk rather than removing it, because the string on the log line
 * is now one a browser chose. Three things follow from that, and they are what this class is:
 *
 * <ul>
 *   <li><b>Nothing a report contains may end a line.</b> Newlines, carriage returns and every
 *       other control character are stripped, so a report cannot forge a second entry and
 *       impersonate any log this platform writes. CodeQL has already caught one log-injection
 *       here; that one arrived over a header rather than a JSON body, but the sink is the same.</li>
 *   <li><b>Nothing a report contains may be unbounded.</b> The DTO caps each field and this
 *       trims further, because a stack trace is the natural place for a page's whole state to
 *       arrive as a string.</li>
 *   <li><b>No browser may write faster than a person can read.</b> A component that throws on
 *       every render would otherwise produce a log line per frame, from every open tab.</li>
 * </ul>
 *
 * <p>The throttle is per-instance and in-memory on purpose. It protects a log volume, not a
 * security boundary — an attacker who wants to fill a disk has cheaper ways — and paying a Redis
 * round trip on the path that handles a page already failing would be the wrong trade.
 */
@Service
@Slf4j
public class ClientErrorReportService {

    /** How much of each field survives onto the log line. */
    private static final int MAX_MESSAGE = 500;
    private static final int MAX_STACK = 2000;
    private static final int MAX_COMPONENT_STACK = 2000;
    private static final int MAX_URL = 300;
    private static final int MAX_RELEASE = 60;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final boolean enabled;
    private final int reportsPerUserPerMinute;
    /**
     * One window per user, for a minute at a time.
     *
     * <p>Expiring, because a plain map here only ever grows: a window lasts a minute and the
     * entry lasted the life of the process, one per user who ever loaded the dashboard. Small
     * each, unbounded together — which is the shape of every slow leak. Bounded as well as
     * expiring, so a burst of distinct users cannot outrun the eviction.
     *
     * <p>Caffeine rather than a scheduled sweep, the way
     * {@code RedisConcurrencyControlService} and {@code MtlsWebClientFactory} already do it.
     */
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

    /**
     * Records one report, or decides not to. Never throws: the caller is a page that has already
     * failed once, and the endpoint answers 202 either way — telling a broken dashboard that its
     * complaint was rate-limited helps nobody.
     *
     * <p>The organization comes off the tenant scope rather than off the caller, which is the
     * rule everywhere in this codebase: whose organization this is is a property of the request,
     * not an argument a handler can get wrong.
     */
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

    /** How many windows are being tracked. Visible so the bound can be asserted, not a metric. */
    long trackedWindows() {
        windows.cleanUp();
        return windows.estimatedSize();
    }

    /** One counter per user per window. Absent users are admitted and start a window. */
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

    /**
     * The path, without the query string. A page's path says which screen broke, which is the
     * whole diagnostic value; its query string is where a share token or a filter carrying
     * customer data would be, and neither belongs in a log.
     */
    private static String pathOf(String url) {
        String cleaned = clean(url, MAX_URL);
        int query = cleaned.indexOf('?');
        String withoutQuery = query >= 0 ? cleaned.substring(0, query) : cleaned;
        int fragment = withoutQuery.indexOf('#');
        return fragment >= 0 ? withoutQuery.substring(0, fragment) : withoutQuery;
    }

    /**
     * Strips every character that could end a line or steer a terminal, collapses the runs that
     * leaves behind, and truncates. Replacing rather than dropping keeps a stack trace readable
     * as one line instead of running its frames together into a single word.
     */
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

    /** A fixed window, replaced wholesale once it has expired. */
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
