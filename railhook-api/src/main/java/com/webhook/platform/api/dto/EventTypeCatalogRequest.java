package com.webhook.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventTypeCatalogRequest {

    @NotBlank(message = "Event type name is required")
    @Pattern(regexp = "^[a-z][a-z0-9_.]*$", message = "Must be lowercase with dots/underscores (e.g. order.created)")
    private String name;

    private String description;
}
