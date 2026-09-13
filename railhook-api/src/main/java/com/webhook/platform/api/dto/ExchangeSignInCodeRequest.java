package com.webhook.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeSignInCodeRequest {

    /** The one-time code the Google sign-in callback put in the dashboard's URL. */
    @NotBlank
    @Size(max = 128)
    private String code;
}
