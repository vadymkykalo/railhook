package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Dry-run delivery result: shows exactly what the endpoint would receive")
public class DeliveryDryRunResponse {

    @Schema(description = "The transformed payload that would be sent as request body")
    private String transformedPayload;

    @Schema(description = "HTTP headers that would be sent with the request")
    private Map<String, String> requestHeaders;

    @Schema(description = "The HMAC signature header value (if endpoint provided)")
    private String signature;

    @Schema(description = "The endpoint URL where the payload would be delivered")
    private String endpointUrl;

    @Schema(description = "Whether the dry-run completed successfully")
    private boolean success;

    @Schema(description = "Any errors or warnings encountered during the dry-run")
    private List<String> errors;

    @Schema(description = "Name of the transformation applied (if any)")
    private String transformationName;

    @Schema(description = "Version of the transformation applied (if any)")
    private Integer transformationVersion;
}
