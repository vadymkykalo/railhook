package com.webhook.platform.api.service.billing;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/** Whole UTC months, half-open, shared so the billing page and admin panel agree. */
public record BillingPeriod(Instant start, Instant end) {

    public static BillingPeriod current(Clock clock) {
        YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        return new BillingPeriod(
                month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC),
                month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }
}
