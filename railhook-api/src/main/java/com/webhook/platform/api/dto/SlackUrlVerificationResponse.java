package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Answer to Slack's url_verification handshake: the challenge Slack sent, echoed back")
public record SlackUrlVerificationResponse(
        @Schema(description = "The challenge from the verified url_verification request",
                example = "3eZbrw1aBm2rZgRNFdxV2595E9CY3gmdALWMmHkvFXO7tYXAYM8P",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String challenge) {
}
