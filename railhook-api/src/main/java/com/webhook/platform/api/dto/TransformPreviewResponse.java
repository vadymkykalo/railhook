package com.webhook.platform.api.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransformPreviewResponse {
    private String outputPayload;
    private String outputHeaders;
    private boolean success;
    private List<String> errors;
}
