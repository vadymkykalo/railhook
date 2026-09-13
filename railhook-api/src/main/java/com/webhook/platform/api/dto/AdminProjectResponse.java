package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** A live project of one organization — its name and age, nothing it contains. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminProjectResponse {

    private UUID id;
    private String name;
    private Instant createdAt;
}
