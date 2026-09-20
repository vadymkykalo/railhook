package com.webhook.platform.api.dto;

import com.webhook.platform.common.transform.ScriptTransformException;
import com.webhook.platform.common.transform.TransformationKind;
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

    @Schema(description = "The language that actually ran")
    private TransformationKind transformationKind;

    @Schema(description = "Everything the script logged, oldest first. Empty for a template.")
    private List<TransformPreviewResponse.ConsoleLine> console;

    @Schema(description = "True when the script asked for the delivery to be dropped. Nothing would be sent, and there is no body to sign.")
    private boolean cancelled;

    @Schema(description = "Why the script cancelled, as the script stated it")
    private String cancelReason;

    @Schema(description = "Wall clock spent inside the script, in milliseconds")
    private long durationMs;

    @Schema(description = "The line in the script the failure came from, 1-based, or null when the failure has no location")
    private Integer errorLine;

    @Schema(description = "Why the script produced nothing, as a value rather than as prose. Same set as the transform preview's.",
            example = "RUNTIME")
    private ScriptTransformException.Reason errorReason;
}
