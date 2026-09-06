package com.webhook.platform.api.domain.enums;

public enum OutboxStatus {
    PENDING,
    SENDING,
    PUBLISHED,
    FAILED,
    DEAD
}
