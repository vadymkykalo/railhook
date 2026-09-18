package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Making a public tester URL: only the challenge answer, when the deployment asks for one. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublicBinCreateRequest {
    private String captchaToken;
}
