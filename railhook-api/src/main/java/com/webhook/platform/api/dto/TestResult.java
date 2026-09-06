package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TestResult {
    private int status;
    private String responseBody;
}
