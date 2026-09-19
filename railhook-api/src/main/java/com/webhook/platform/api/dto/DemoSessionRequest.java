package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Opening a public demo session. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DemoSessionRequest {

    /** The challenge answer, when the deployment asks for one. */
    private String captchaToken;
}
