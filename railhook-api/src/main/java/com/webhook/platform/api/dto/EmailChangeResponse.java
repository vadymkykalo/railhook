package com.webhook.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * {@code applied} is true when an unverified account's address changed at once. A verified
 * account's change waits at {@code pendingEmail} until the link sent there is opened.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmailChangeResponse {

    private String email;

    private String pendingEmail;

    private Instant pendingExpiresAt;

    private boolean applied;
}
