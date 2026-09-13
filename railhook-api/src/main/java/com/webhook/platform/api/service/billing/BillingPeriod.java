package com.webhook.platform.api.service.billing;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * The month usage is measured over: whole UTC months, half-open.
 *
 * <p>One definition for the tenant's billing page and the operator's panel, so "events this
 * month" is the same number on both sides of a support conversation.
 */
public record BillingPeriod(Instant start, Instant end) {

    public static BillingPeriod current(Clock clock) {
        YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        return new BillingPeriod(
                month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC),
                month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }
}
