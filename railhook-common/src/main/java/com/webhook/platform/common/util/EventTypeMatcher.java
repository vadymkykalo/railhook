package com.webhook.platform.common.util;

import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code order.*} matches exactly one segment, {@code order.**} any number, {@code **} everything.
 */
public final class EventTypeMatcher {

    /** Pre-split segments, to keep String.split() off the ingest hot path. */
    private static final ConcurrentHashMap<String, String[]> PATTERN_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String[]> EVENT_CACHE = new ConcurrentHashMap<>();

    private EventTypeMatcher() {
    }

    public static boolean matches(String pattern, String eventType) {
        if (pattern == null || eventType == null) {
            return false;
        }
        if (pattern.equals(eventType)) {
            return true;
        }
        if (pattern.equals("**")) {
            return true;
        }

        String[] patternParts = PATTERN_CACHE.computeIfAbsent(pattern, p -> p.split("\\."));
        String[] eventParts = EVENT_CACHE.computeIfAbsent(eventType, e -> e.split("\\."));

        return matchParts(patternParts, 0, eventParts, 0);
    }

    private static boolean matchParts(String[] pattern, int pi, String[] event, int ei) {
        while (pi < pattern.length && ei < event.length) {
            String seg = pattern[pi];
            if ("**".equals(seg)) {
                if (pi == pattern.length - 1) {
                    return true;
                }
                for (int skip = ei; skip <= event.length; skip++) {
                    if (matchParts(pattern, pi + 1, event, skip)) {
                        return true;
                    }
                }
                return false;
            } else if ("*".equals(seg)) {
                pi++;
                ei++;
            } else {
                if (!seg.equals(event[ei])) {
                    return false;
                }
                pi++;
                ei++;
            }
        }

        while (pi < pattern.length && "**".equals(pattern[pi])) {
            pi++;
        }

        return pi == pattern.length && ei == event.length;
    }

    public static boolean isWildcard(String pattern) {
        return pattern != null && pattern.contains("*");
    }

    public static boolean isValidPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String[] parts = pattern.split("\\.", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                return false;
            }
            if ("*".equals(part) || "**".equals(part)) {
                continue;
            }
            if (!part.matches("[a-z][a-z0-9_]*")) {
                return false;
            }
        }
        return true;
    }
}
