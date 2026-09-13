package com.webhook.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Where an account's address stands.
 *
 * <p>{@code applied} says which of the two answers a request got: an unverified account's address
 * changed at once ({@code email} is the new one, and it still has to be verified), or a verified
 * account's change is waiting at {@code pendingEmail} until the link sent there is opened.
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
