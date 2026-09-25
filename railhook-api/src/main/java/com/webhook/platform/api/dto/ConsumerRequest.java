package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsumerRequest {

    @Schema(description = "Your own identifier for this user, unique within the project — "
            + "typically the user or tenant id in your database.")
    @NotBlank(message = "externalId is required")
    @Size(max = 255, message = "externalId must be at most 255 characters")
    private String externalId;

    @Schema(description = "Shown at the top of the portal. Absent: the externalId on create, unchanged on update.")
    @Size(max = 255, message = "name must be at most 255 characters")
    private String name;
}
