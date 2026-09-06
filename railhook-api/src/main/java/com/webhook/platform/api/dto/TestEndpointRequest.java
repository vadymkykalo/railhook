package com.webhook.platform.api.dto;

import lombok.Data;

@Data
public class TestEndpointRequest {
    private String name;
    private String description;
    private Integer ttlHours = 24;
}
