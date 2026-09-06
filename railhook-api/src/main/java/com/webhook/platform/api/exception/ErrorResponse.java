package com.webhook.platform.api.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private String error;
    private String message;
    private Integer status;
    private Map<String, String> fieldErrors;

    public ErrorResponse(String error, String message, Integer status) {
        this.error = error;
        this.message = message;
        this.status = status;
    }
}
