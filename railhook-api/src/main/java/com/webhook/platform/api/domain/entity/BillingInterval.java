package com.webhook.platform.api.domain.entity;

import java.time.Duration;
import java.time.Period;

public enum BillingInterval {
    MONTHLY(Period.ofMonths(1)),
    YEARLY(Period.ofYears(1));

    private final Period period;

    BillingInterval(Period period) {
        this.period = period;
    }

    public Period getPeriod() {
        return period;
    }
}
